#!/usr/bin/env python3
"""Host-in-the-loop E2E for the Bluetrack touchpad + keyboard HID paths.

Drives the phone over `adb` while the macOS HID inspector (`watch`) records
what the **host actually receives**, then asserts the expected HID report
IDs arrived. This validates the real wire as the Mac sees it — not just the
Android-side pipeline.

HARDWARE-IN-THE-LOOP — this is NOT a CI test:
  * the phone must be HID-paired to THIS Mac (Bluetrack "connected"),
  * Terminal/iTerm needs Input Monitoring permission for the inspector,
  * during the run the phone really drives the Mac — the cursor moves and
    typed characters land in the focused window. The keyboard phase brings
    a scratch TextEdit document to the front so "abc" types there.

Report IDs (must match BleHidGateway): mouse = 1, gamepad = 2, keyboard = 3.

Usage:
  python3 host/e2e/input_e2e.py [--adb PATH] [--device SERIAL] [--name NAME]
                                [--seconds N] [--keep-going]

Coordinates in DRIVE_* are phone-pixel and device-specific; tune the few
constants below for your panel if a phase reports "no events".
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
INSPECTOR_DIR = os.path.join(HERE, "..", "macos-hid-inspector")
INSPECTOR_BIN = os.path.join(INSPECTOR_DIR, ".build", "debug", "bluetrack-hid-inspector")

PACKAGE = "dev.xd.bluetrack"
ACTIVITY = f"{PACKAGE}/.MainActivity"

MOUSE_REPORT_ID = "1"
KEYBOARD_REPORT_ID = "3"

# Phone-pixel coordinates (1080x2400 reference). Tune for your device.
TOUCHPAD_SWIPE = (540, 1300, 540, 850)  # a clear drag over the touchpad surface
SCROLL_TO_KB = (540, 1250, 540, 450)    # scroll a card area to reveal the keyboard bar
KEYBOARD_BAR_TAP = (300, 1459)          # the "Keyboard" bar after scrolling


class Adb:
    def __init__(self, adb: str, device: str | None):
        self.adb = adb
        self.device = device

    def __call__(self, *args: str, check: bool = True) -> str:
        cmd = [self.adb]
        if self.device:
            cmd += ["-s", self.device]
        cmd += list(args)
        out = subprocess.run(cmd, capture_output=True, text=True)
        if check and out.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
        return out.stdout

    def shell(self, *args: str, check: bool = True) -> str:
        return self("shell", *args, check=check)

    def first_device(self) -> str | None:
        lines = self("devices", check=False).splitlines()[1:]
        for line in lines:
            if "\tdevice" in line:
                return line.split("\t", 1)[0]
        return None


def ensure_inspector(force: bool) -> None:
    if os.path.exists(INSPECTOR_BIN) and not force:
        print(f"• using existing inspector: {INSPECTOR_BIN}")
        return
    print("• building macos-hid-inspector … (one-time; pass --build to force)")
    subprocess.run(
        ["swift", "build", "-c", "debug"],
        cwd=INSPECTOR_DIR,
        check=True,
    )
    if not os.path.exists(INSPECTOR_BIN):
        raise RuntimeError(f"inspector binary not found at {INSPECTOR_BIN}")


def capture(name_filter: str, seconds: float, drive, report_path: str) -> dict:
    """Run `watch --report` while `drive()` runs, return the parsed HID block."""
    proc = subprocess.Popen(
        [
            INSPECTOR_BIN, "watch",
            "--name", name_filter,
            "--seconds", str(seconds),
            "--no-bluetooth", "--no-elements",
            "--report", report_path,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    # Let IOHID attach + the device(s) open before driving input.
    time.sleep(2.0)
    try:
        drive()
    finally:
        proc.wait(timeout=seconds + 15)
    with open(report_path) as f:
        return json.load(f)["hid"]


def phase(label: str, hid: dict, want_report: str, keep_going: bool) -> bool:
    counts = hid.get("reportEventCounts", {})
    events = hid.get("eventCount", 0)
    got = counts.get(want_report, 0)
    ok = hid.get("exitCode", 1) == 0 and events > 0 and got > 0
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {label}: eventCount={events} report{want_report}={got} "
          f"(all reports: {counts or '{}'})")
    if not ok and not keep_going:
        print(f"    → no report-{want_report} events. Is Bluetrack connected to this "
              f"Mac, Input Monitoring granted, and the on-screen target hit? Tune "
              f"the DRIVE_* coords.")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=os.environ.get(
        "BT_ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")))
    ap.add_argument("--device", default=os.environ.get("BT_DEVICE"))
    ap.add_argument("--name", default="Bluetrack",
                    help="IOHID product/transport substring for the paired phone")
    ap.add_argument("--seconds", type=float, default=7.0)
    ap.add_argument("--keep-going", action="store_true",
                    help="run all phases even if one fails")
    ap.add_argument("--build", action="store_true",
                    help="force-rebuild the inspector (otherwise reuse the binary)")
    args = ap.parse_args()

    adb = Adb(args.adb, args.device or None)
    if not adb.device:
        adb.device = adb.first_device()
    if not adb.device:
        print("No adb device. Plug in the phone / check `adb devices`.")
        return 2
    print(f"• adb device: {adb.device}")

    ensure_inspector(args.build)

    tmp = tempfile.mkdtemp(prefix="bt-e2e-")
    results = []

    # ── Touchpad ─────────────────────────────────────────────
    def drive_touchpad():
        adb.shell("am", "force-stop", PACKAGE)
        adb.shell("am", "start", "-n", ACTIVITY)
        time.sleep(3.0)
        # A few drags over the touchpad surface → relative mouse reports.
        for _ in range(4):
            adb.shell("input", "swipe", *map(str, TOUCHPAD_SWIPE), "120")
            time.sleep(0.3)

    print("\n▶ touchpad phase (cursor will move on this Mac)…")
    tp = capture(args.name, args.seconds, drive_touchpad, os.path.join(tmp, "tp.json"))
    results.append(phase("touchpad → mouse reports", tp, MOUSE_REPORT_ID, args.keep_going))

    # ── Keyboard ─────────────────────────────────────────────
    def drive_keyboard():
        # Scratch window so the relayed "abc" types somewhere harmless.
        subprocess.run(["osascript", "-e",
                        'tell application "TextEdit" to activate',
                        "-e", 'tell application "TextEdit" to make new document'],
                       check=False)
        time.sleep(1.0)
        adb.shell("am", "force-stop", PACKAGE)
        adb.shell("am", "start", "-n", ACTIVITY)
        time.sleep(3.0)
        for _ in range(3):  # reveal the keyboard bar
            adb.shell("input", "swipe", *map(str, SCROLL_TO_KB), "250")
            time.sleep(0.8)
        adb.shell("input", "tap", *map(str, KEYBOARD_BAR_TAP))  # open the relay
        time.sleep(1.5)
        adb.shell("input", "text", "abc")
        time.sleep(0.8)

    print("\n▶ keyboard phase (a TextEdit scratch doc is brought to front)…")
    kb = capture(args.name, args.seconds, drive_keyboard, os.path.join(tmp, "kb.json"))
    results.append(phase("keyboard → key reports", kb, KEYBOARD_REPORT_ID, args.keep_going))

    print()
    if all(results):
        print("E2E PASS — host received both touchpad (report 1) and keyboard (report 3).")
        return 0
    print("E2E FAIL — see phases above.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
