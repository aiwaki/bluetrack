# Gamepad Debugging

Bluetrack may appear in desktop Bluetooth settings as the Android phone name
instead of `Bluetrack Pro Engine`. That is expected on some hosts: the classic
Bluetooth bond is still the phone, while the HID service underneath carries the
mouse and gamepad report map.

## macOS HID Inspector

The host-side inspector checks what macOS sees below browsers and games:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector scan --name Bluetrack --no-bluetooth
```

The default scan looks for the Bluetrack name and also recognizes the current
composite HID shape, so it can select a phone-named device such as:

```text
aiwaki [0x01:0x02 Mouse]
```

A healthy composite descriptor has:

- report 1: mouse buttons, X, Y, wheel;
- report 2: Game Pad collection, 16 buttons, hat switch, X, Y, Z, Rz.

To verify live input, switch Bluetrack to Gamepad and run:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector watch --name Bluetrack --seconds 15 --no-bluetooth --no-elements
```

If report 2 events appear, Android is sending gamepad HID to macOS. If a browser
tester still waits for input, the problem is above raw IOHID: browser Gamepad API
activation, macOS game-controller mapping, or the page being focused too late.
The Gamepad API exposes already-connected pads only after a user gamepad
gesture; the W3C spec describes this around `navigator.getGamepads()` exposure
and the `gamepadconnected` event.

Reference: https://www.w3.org/TR/gamepad/

## Current Host Evidence

On the tested Mac, macOS selected the Android phone-named device as a Bluetooth
HID mouse primary usage, but the same report map contained the Game Pad
collection. Live IOHID watch confirmed report 2 X/Y axis events while Bluetrack
was in Gamepad mode.

That means:

- HID pairing is working;
- Android sends report 2;
- the browser issue is not BLE feedback and not basic HID transport;
- activation must happen after the target browser/game page is already focused.

Bluetrack therefore sends a short automatic gamepad discovery wake train on
Gamepad activation and, rate-limited, at the start of Gamepad touch gestures.
The wake train primes the host with a neutral report, presses a high-numbered
discovery button, then releases back to neutral.

## BLE Feedback Companion

The same SwiftPM tool can also exercise the BLE feedback channel from macOS
without leaving Swift. It mirrors `android/tools/ble_encrypt_sender.py` so the
two paths can be diagnosed with one binary:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    feedback --seconds 15 --interval-ms 5
```

The `feedback` subcommand scans for the BLE feedback service UUID, connects to
the advertiser, discovers the encrypted-correction characteristic, and writes
AES-128-CTR-encrypted `(counter, dx, dy)` packets at the configured cadence.
Pair it with a separate `watch` run on a second terminal to confirm both the
BLE write side and the HID input side are healthy at the same time, or use
`companion` to run both on a single run loop and see a combined verdict:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    companion --seconds 15 --interval-ms 5
```

`companion` discovers the Bluetrack composite IOHID device, schedules HID
input callbacks, primes the BLE feedback writer, runs both for the configured
duration, and prints a combined `HID watch: PASS|FAIL / BLE feedback: PASS|FAIL`
verdict. The exit code is non-zero if either path fails.

Add `--report path.json` to persist the verdict, exit codes, event counters,
peripheral identity, and timings. The output is pretty-printed JSON with
`sortedKeys` so checked-in snapshots from multiple Mac/phone combinations
diff cleanly:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    companion --seconds 15 --report ~/bluetrack-snapshot.json
```

The schema is versioned via `tool` / `toolVersion` fields inside the JSON.

To validate the crypto contract without touching Bluetooth (useful on a Mac
that only ships CommandLineTools):

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector selftest
```

`selftest` round-trips a few `(counter, dx, dy)` tuples through `FeedbackCrypto`
and asserts the UUIDs and frame layout match the Android `PayloadDecryptor`.

## Descriptor Cache

macOS and Windows cache Bluetooth HID report maps. After any descriptor change,
forget the old phone/Bluetrack Bluetooth device on the host and pair again once.
App code cannot force a desktop host to discard an old HID descriptor cache.
