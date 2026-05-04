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

As of 2026-05-03:

- PR #3 was merged into `main`.
- Current follow-up work may be on `codex/add-macos-hid-inspector`.
- Check `git log -1 --oneline` for the current head; `main` carries the build
  hardening, Bluetooth diagnostics, project context, compatibility cockpit,
  event timeline, touchpad input work, and HID reconnect/input flow.
- Re-check GitHub Actions after every push.
- Local validation passed with Android Studio's bundled Java runtime:
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug`.
- Debug APK path after build:
  `android/app/build/outputs/apk/debug/app-debug.apk`.

The app now has:

- Bluetooth permission handling for Android 12+ nearby-device permissions.
- A Bluetooth enable request flow.
- Autopilot pairing: when Bluetooth is enabled and no bonded host exists,
  Bluetrack asks Android to open the discoverability window automatically.
- Application-scoped Bluetooth ownership plus a foreground HID keep-alive
  service, so Activity destruction/backgrounding does not intentionally
  unregister the HID app.
- Automatic HID host connection after registration, pairing result, foreground
  compatibility refresh, or a quiet 5-second maintenance refresh, preferring the
  best bonded computer-class host and ignoring bonded audio/accessory devices
  such as AirPods, headphones, speakers, keyboards, mice, and trackpads.
- Composite HID registration: mouse and gamepad report descriptors are
  registered together so mode switching does not unregister the HID app or break
  the host connection.
- A gamepad descriptor that advertises a gamepad usage with 16 buttons, a
  neutral hat switch/D-pad, and four axes, plus a visible automatic button wake
  train on gamepad connect/mode activation/first input and a rate-limited
  discovery wake at the start of Gamepad touch gestures to help host software
  and browser Gamepad API pages enumerate the controller after the target page
  is already focused.
- A calmer primary UI with Ready/Connecting/Input live/Needs attention state,
  compact counters, and quieter system/activity details. Manual
  pairing/connect/refresh buttons were removed from the primary surface; Android
  system confirmation prompts are still required.
- Touchpad input capture in addition to external relative mouse hover motion.
- Cursor smoothing improvements: the touchpad consumes coalesced historical
  touch samples and the translation engine preserves fractional mouse deltas
  between HID reports before integer quantization. UI callbacks enqueue motion
  only; a background 8 ms input pacer drains accumulated deltas into HID reports
  to avoid bursty touch-event batches and reduce visible stutter. Historical
  touch samples are batched per Android motion event before entering the
  ViewModel, and high-rate HID report counters/telemetry are throttled before
  Compose observes them so the diagnostic UI does not compete with touch
  delivery. HID transport is decoupled from the pacer with a small output
  buffer: mouse deltas coalesce instead of dropping motion, gamepad reports keep
  a bounded short queue, and a dedicated sender absorbs short Bluetooth stalls
  without stopping the input clock. The sender has a small adaptive governor:
  after measured `sendReport` backpressure it briefly slows catch-up sends so
  Bluetooth can drain and mouse deltas coalesce instead of causing repeated
  transport stalls.
- Hidden input diagnostics around touch enqueue, pacer ticks, queue latency, and
  HID send duration. They do not alter movement and only emit throttled logcat
  warnings under tag `BluetrackInput` when thresholds are crossed. Touch
  diagnostics reset at gesture start so long pauses between gestures do not
  pollute micro-jank readings. Output queue latency is also logged so Bluetooth
  transport stalls can be separated from touch or pacer stalls.
- A short-horizon touch motion predictor fills small gaps between Android touch
  events. Predicted mouse motion is tracked as debt and reconciled against the
  next real touch event, so brief touch-delivery holes feel smoother without
  long runaway drift.
- Status rows for HID, BLE feedback, pairing, host, input source, and error text.
- HID Device registration for mouse/gamepad modes.
- A feedback GATT server with connectable BLE advertising.
- A Python sender that can scan for the feedback service UUID.
- Claude handoff files: `CLAUDE.me` and `.claude/rules/`.
- A macOS SwiftPM IOHID inspector under `host/macos-hid-inspector/` and the
  workflow in `docs/GAMEPAD_DEBUGGING.md`.

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
the active branch, and any open PR before editing. Keep Bluetooth HID and BLE
feedback separate in your mental model and in the UI. Improve real hardware
debuggability over speculative features. Make failures visible and actionable.
Validate with Android Studio's bundled JBR, keep CI green, update the project
memory when the operating model changes, and push through the active PR.
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
5. Accept the Android discoverability prompt when Bluetrack opens it.
6. After pairing/bonding, return to the app. It should run the HID keep-alive
   service, refresh compatibility, and auto-connect the bonded host. Minimize
   the Activity to verify the HID host remains connected.

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
path. Inspect the timeline for `connect returned false` or connection-state
callbacks. After the composite mouse/gamepad descriptor change, macOS may need
one manual "Forget This Device" and fresh pairing to drop the old mouse-only
descriptor cache; subsequent mode switching should not require reconnecting.
The latest gamepad descriptor changed from the earlier joystick-like/minimal
report to a gamepad usage with 16 buttons, a neutral hat switch/D-pad, and four
axes; forget and re-pair once if macOS still has the old cached descriptor.
Gamepad mode will not move the macOS cursor; verify it in a game, emulator, or
browser gamepad tester after switching the app to `Gamepad`. Browser testers
that say "press any button" should catch the automatic visible wake train, but
the page may need to be open before switching to `Gamepad` or before the first
gamepad input. Bluetrack now also sends a rate-limited discovery wake at the
start of Gamepad touch gestures so a page opened after activation can still
observe a real button gesture. On macOS, the device may appear as the phone name
with primary usage Mouse while still exposing Game Pad report 2. Use:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector scan --name Bluetrack --no-bluetooth
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector watch --name Bluetrack --seconds 15 --no-bluetooth --no-elements
```

If report 2 events arrive, Android HID delivery is working and the remaining
problem is browser Gamepad API activation or host game-controller mapping.

For feedback testing:

```bash
python android/tools/ble_encrypt_sender.py
```

Or with a known BLE address:

```bash
python android/tools/ble_encrypt_sender.py --address 00:11:22:33:44:55
```

For input jank diagnostics while reproducing rare cursor pauses:

```bash
adb logcat -s BluetrackInput Bluetrack
```

## Known Limitations

- HID Device profile support is device/firmware-dependent.
- The app has a diagnostic touchpad path, but it is not yet a polished touchpad
  product surface.
- If multiple bonded devices exist, the automatic HID connect path prefers
  computer-class Bluetooth devices and then falls back by computer-like names.
  It should not try to connect bonded audio/accessory devices such as AirPods.
- Android does not allow third-party apps to bypass system confirmation dialogs
  for Bluetooth enable/discoverability/pairing.
- Android requires an ongoing foreground-service notification for the background
  HID keep-alive path.
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
- Combine `feedback` + `watch` into a single `companion` subcommand so one
  invocation reports both BLE write health and HID input arrival.
- Add a lightweight hardware report export for compatibility snapshots and
  event timeline entries.

UX:

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
