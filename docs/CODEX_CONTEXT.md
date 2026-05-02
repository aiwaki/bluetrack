# Codex Context: Bluetrack Pro Engine

This file is the handoff memory for future chats. It should let a fresh Codex
session understand the project in minutes instead of reconstructing it from
commit archaeology.

## North Star

Bluetrack is an Android-native input translation lab:

- Capture relative mouse/trackpad movement on Android.
- Translate it into Bluetooth HID mouse or gamepad reports.
- Pair the Android device with a PC as the Bluetooth HID input device.
- Accept encrypted BLE feedback packets from the host to adjust output.

The useful product feeling is not a marketing page. It is a reliable diagnostic
tool that tells the operator exactly which Bluetooth layer is alive, blocked, or
unsupported.

## Current State

As of 2026-05-02:

- Branch: `codex/harden-android-build-tests`.
- Draft PR: `https://github.com/aiwaki/bluetrack/pull/3`.
- Check `git log -1 --oneline` for the current head; this branch carries the
  build hardening, Bluetooth diagnostics, project context, compatibility
  cockpit, event timeline, and touchpad input work.
- GitHub Actions for PR #3 passed after the Bluetooth diagnostic and context
  changes. Re-check after every push.
- Local validation passed with Android Studio's bundled Java runtime:
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug`.
- Debug APK path after build:
  `android/app/build/outputs/apk/debug/app-debug.apk`.

The app now has:

- Bluetooth permission handling for Android 12+ nearby-device permissions.
- A Bluetooth enable request flow.
- A `Pair with PC` action that launches Android's discoverability prompt.
- Automatic HID host connection after registration, compatibility refresh, or
  pairing result, plus a `Connect Host` fallback for the best bonded
  computer-class host.
- Composite HID registration: mouse and gamepad report descriptors are
  registered together so mode switching does not unregister the HID app or break
  the host connection.
- A cockpit UI with actions, counters, compatibility status, and event timeline.
- Touchpad input capture in addition to external relative mouse hover motion.
- Status rows for HID, BLE feedback, pairing, host, input source, and error text.
- HID Device registration for mouse/gamepad modes.
- A feedback GATT server with connectable BLE advertising.
- A Python sender that can scan for the feedback service UUID.

## Mental Model

There are two Bluetooth paths. Keep them separate in your head and in the UI.

### HID Path

The Android device registers as a HID Device using Android's `BluetoothHidDevice`
API. A PC must pair to it as the host. If the Android firmware does not expose
`BluetoothProfile.HID_DEVICE` to apps, HID will not work on that device.

Key file:

```text
android/app/src/main/kotlin/dev/xd/bluetrack/ble/BleHidGateway.kt
```

### Feedback Path

The Android app also runs a BLE GATT server for encrypted host feedback. That
path advertises service UUID `0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263` and accepts
writes to characteristic UUID `4846ff87-f2d4-4df2-9500-9bf8ed23f9e6`.

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

This BLE path is not what makes the phone show up as a mouse/gamepad on the PC.
It is the calibration/correction side channel.

## How To Resume Work

Run:

```bash
git status -sb
git log --oneline --decorate -5
gh pr list --head "$(git branch --show-current)" --json number,title,url,state,isDraft
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Then validate before and after meaningful changes:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
cd ..
python3 -m py_compile android/tools/ble_encrypt_sender.py
```

## Expert Continuation Prompt

Use this when you want the next Codex session to operate at full context:

```text
Continue Bluetrack as a senior Android/Bluetooth engineer and diagnostic-tool
designer. Read AGENTS.md and docs/CODEX_CONTEXT.md first. Check git status,
the active branch, and PR #3 before editing. Keep Bluetooth HID and BLE feedback
separate in your mental model and in the UI. Improve real hardware debuggability
over speculative features. Make failures visible and actionable. Validate with
Android Studio's bundled JBR, keep CI green, update the project memory when the
operating model changes, and push through the existing draft PR.
```

## Hardware Validation Script

On Android:

1. Install the debug APK.
2. Open Bluetrack.
3. Grant Bluetooth nearby-device permissions.
4. Confirm the status rows:
   - `HID ready (...)` is required for HID pairing.
   - `HID profile unavailable` means the device/firmware cannot run this HID
     path as a third-party app.
   - `Advertising feedback service` means the BLE correction channel is visible.
   - The compatibility panel shows adapter, advertiser, HID proxy, scan mode,
     and bonded-device state.
   - The timeline should show the latest permission, HID, feedback, pairing,
     input, and report events.
5. Tap `Pair with PC` and accept the Android discoverability prompt.
6. After pairing/bonding, return to the app. It should auto-connect the bonded
   host. Tap `Connect Host` only if the HID row does not become `Connected`
   automatically.

On PC:

1. Open Bluetooth settings.
2. Add a new Bluetooth device.
3. Pair with `Bluetrack Pro Engine` if it appears.
4. Keep the Android app foregrounded.
5. Move a mouse/trackpad connected to Android or drag inside the app's input
   surface to use the touchpad path.

macOS should behave as a normal Bluetooth HID host. If Android and macOS are
bonded but the app stays in pairing/discoverable state and HID never reaches
`Connected`, the missing piece is HID host connection, not the BLE feedback
path. Use `Connect Host` and inspect the timeline for `connect returned false`
or connection-state callbacks. After the composite mouse/gamepad descriptor
change, macOS may need one manual "Forget This Device" and fresh pairing to drop
the old mouse-only descriptor cache; subsequent mode switching should not
require reconnecting.

For feedback testing:

```bash
python android/tools/ble_encrypt_sender.py
```

Or with a known BLE address:

```bash
python android/tools/ble_encrypt_sender.py --address 00:11:22:33:44:55
```

## Known Limitations

- HID Device profile support is device/firmware-dependent.
- The app has a diagnostic touchpad path, but it is not yet a polished touchpad
  product surface.
- If multiple bonded devices exist, `Connect Host` prefers computer-class
  Bluetooth devices and then falls back by name.
- Mode switching should not call `unregisterApp()`. The gateway now registers a
  composite descriptor once and only changes the active report path.
- Crypto is prototype-only: static key, static salt, no authentication, no
  session negotiation.
- The feedback writer is a reference loop, not a full host companion app.
- Runtime Bluetooth validation still needs real Android hardware plus a PC.

## Roadmap Ideas

High leverage:

- Run the cockpit on real Android hardware and capture the first compatibility
  snapshots for devices that do and do not expose HID Device support.
- Add Android unit tests around `GatewayStatus` transitions by extracting a
  small state reducer from `BleHidGateway`.
- Build a simple host companion CLI/UI that:
  - discovers the BLE feedback service,
  - writes correction packets,
  - displays whether HID input is arriving.
- Add a lightweight hardware report export for compatibility snapshots and
  event timeline entries.

UX:

- Add explicit mode cards for mouse vs gamepad.
- Add live report counters and last packet timestamps.
- Refine the diagnostic touchpad into a more deliberate control surface.

Security:

- Replace static AES material with per-session key agreement.
- Add message authentication or authenticated encryption.
- Validate peer identity before accepting correction writes.

## User Preference For This Project

The user wants Codex to be proactive and is comfortable granting broad control
when the direction is clear. Be decisive, but keep evidence visible: build/test
results, PR links, and concrete hardware caveats matter here.

## Handoff Prompt

If a future chat starts cold, this is the fastest prompt:

```text
Continue Bluetrack. Read AGENTS.md and docs/CODEX_CONTEXT.md, check git
status and the current PR, then continue from the live repository state.
Important: Bluetooth HID and BLE feedback are separate paths, and Gradle should
use Android Studio's bundled JBR on this machine.
```
