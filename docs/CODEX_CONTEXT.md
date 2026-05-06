# Codex Context: Bluetrack Pro Engine

Long-form mental model + hardware validation script + roadmap. The
project shape, code map, and operating prompt live in `CLAUDE.md` and
`AGENTS.md`; project rules live in `.claude/rules/00-project.md`. Read
those first.

## North Star

Bluetrack is an Android-native input translation lab:

- Capture relative mouse/trackpad movement on Android.
- Translate it into Bluetooth HID mouse or gamepad reports.
- Pair the Android device with a PC as the Bluetooth HID input device.
- Accept encrypted BLE feedback packets from the host to adjust output.

The useful product feeling is not a marketing page. It is a reliable
diagnostic tool that tells the operator exactly which Bluetooth layer is
alive, blocked, or unsupported.

## Mental Model

Two Bluetooth paths. Keep them separate in your head and in the UI.

### HID path

Android registers as a HID Device using `BluetoothHidDevice`. A PC pairs
to it as the host. If the firmware does not expose
`BluetoothProfile.HID_DEVICE` to apps, HID will not work on that device.

Owner: `android/app/src/main/kotlin/dev/xd/bluetrack/ble/BleHidGateway.kt`.

### Feedback path

Android also runs a BLE GATT server for encrypted host feedback.
Service UUID `0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263` accepts writes on
characteristic UUID `4846ff87-f2d4-4df2-9500-9bf8ed23f9e6`.

Packet shape:

```text
[0..3]  counter_le
[4..11] AES-128-CTR ciphertext for little-endian float dx, float dy
```

Static prototype crypto:

```text
KEY    = "BluetrackKey1234"
SALT12 = "BluetrackSal"
```

This BLE path is not what makes the phone show up as a mouse/gamepad on
the PC — it is the calibration/correction side channel.

## Hardware Validation Script

On Android:

1. Install the debug APK
   (`android/app/build/outputs/apk/debug/app-debug.apk`).
2. Open Bluetrack and grant Bluetooth nearby-device permissions.
3. Confirm the status rows:
   - `HID ready (...)` is required for HID pairing.
   - `HID profile unavailable` means the device/firmware cannot run this
     HID path as a third-party app.
   - `Advertising feedback service` means the BLE correction channel is
     visible.
4. Accept the Android discoverability prompt.
5. After pairing/bonding, return to the app. It should run the HID
   keep-alive service, refresh compatibility, and auto-connect the bonded
   host. Minimize the Activity to verify HID host remains connected.

On PC:

1. Open Bluetooth settings, add a new device.
2. Pair with `Bluetrack Pro Engine` if it appears, or with the
   phone-named entry that exposes the HID service (see
   `docs/GAMEPAD_DEBUGGING.md`).
3. Keep the Android app foregrounded.
4. Move a mouse/trackpad connected to Android or drag inside the app's
   input surface.

After descriptor changes, macOS may need "Forget This Device" + fresh
pairing to drop the cached HID report map. Subsequent mode switching
should not require reconnecting.

Gamepad-mode verification: in a browser gamepad tester or game.
Inspector help:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    companion --seconds 15 --report host/snapshots/<file>.json
```

If report 2 events arrive on the IOHID side, Android HID delivery is
working and any remaining issue is browser Gamepad API activation or
host game-controller mapping.

Feedback-only sender:

```bash
python android/tools/ble_encrypt_sender.py [--address 00:11:22:33:44:55]
```

Input jank diagnostics:

```bash
adb logcat -s BluetrackInput Bluetrack
```

## Known Limitations

- HID Device profile support is device/firmware-dependent.
- The diagnostic touchpad is not yet a polished product surface.
- Auto-connect prefers computer-class hosts; ignores audio/accessory
  bonded devices (AirPods, headphones, keyboards, mice, trackpads).
- Android system confirmation dialogs for enable/discoverability/pairing
  cannot be bypassed by app code.
- Android requires an ongoing foreground-service notification for the
  HID keep-alive path.
- Mode switching never calls `unregisterApp()`; one composite descriptor
  is registered and only the active report path changes.
- Crypto is prototype-only: static key, static salt, no authentication,
  no session negotiation.
- Runtime Bluetooth validation still needs real Android hardware plus a
  PC.

## Roadmap

High leverage:

- Capture more `host/snapshots/` entries on different Mac+phone combos
  (especially with the phone in Gamepad mode at capture time).
- Cross-feed BLE peripheral name into HID-side filter automatically in
  `companion`, removing the manual `--name <phone>` rerun the
  `InspectorHints` tip currently surfaces.

UX:

- Refine the diagnostic touchpad into a more deliberate control surface
  once the new UI artifacts land.

Security:

- Replace static AES material with per-session key agreement (ECDH).
- Add authenticated encryption (AES-GCM or ChaCha20-Poly1305).
- Validate peer identity before accepting correction writes.

## User Preference

The user wants Codex/Claude to be proactive and is comfortable granting
broad control when the direction is clear. Be decisive, but keep
evidence visible: build/test results, PR links, and concrete hardware
caveats matter here.
