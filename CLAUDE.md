# CLAUDE.md: Bluetrack Project Context

Start here before editing Bluetrack. Then read `AGENTS.md` and
`docs/CODEX_CONTEXT.md`.

## What Bluetrack Is

Bluetrack is a native Android/Kotlin app that turns an Android phone into a
Bluetooth HID input device. The phone is the HID device. The computer is the HID
host. The app currently supports mouse and gamepad report paths plus a separate
BLE feedback channel for encrypted correction packets.

The product goal is Apple-like automatic behavior: no manual app buttons for
normal setup, clear state, automatic pairing/connect where Android permits it,
and smooth input that hides platform jitter when it can do so honestly.

## Current Branch And PR

- PR #3 was merged into `main`.
- Current gamepad visibility work may be on `codex/add-macos-hid-inspector`.
- Build root: `android/`
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Always check the live state first:

```bash
git status -sb
git log --oneline --decorate -5
gh pr list --head "$(git branch --show-current)" --json number,title,url,state,isDraft
```

Use Android Studio's bundled JBR on this machine:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Primary validation:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
cd ..
python3 -m py_compile android/tools/ble_encrypt_sender.py
```

## System Model

Keep these two Bluetooth paths separate:

- HID path: Android registers `BluetoothHidDevice` with a composite mouse and
  gamepad descriptor. This is what makes the phone act like an input device.
- Feedback path: Android runs a BLE GATT server for encrypted correction writes.
  This does not make the phone appear as a mouse/gamepad.

The HID path is hardware/firmware dependent. If Android does not expose
`BluetoothProfile.HID_DEVICE`, the app cannot force it.

## Important Files

- `android/app/src/main/kotlin/dev/xd/bluetrack/MainActivity.kt`: Compose UI,
  permission prompts, Bluetooth readiness flow, touchpad surface.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/BleHidGateway.kt`: HID
  registration, host auto-connect, descriptor, feedback GATT server.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/GatewayStatusReducer.kt`:
  pure-Kotlin status/event reducer used by `BleHidGateway.updateStatus`. Side
  effects (logcat, StateFlow write) stay in the gateway; the reducer is
  covered by JVM tests in `GatewayStatusReducerTest`.
- `android/app/src/main/kotlin/dev/xd/bluetrack/engine/TranslationEngine.kt`:
  mouse and gamepad report generation.
- `android/app/src/main/kotlin/dev/xd/bluetrack/engine/GamepadReportFormat.kt`:
  current 7-byte gamepad report shape.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ui/HidOutputBuffer.kt`:
  decoupled HID transport buffer.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ui/HidTransportGovernor.kt`:
  adaptive sender backpressure governor.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ui/TouchMotionPredictor.kt`:
  short-horizon touch gap filler with debt reconciliation.
- `host/macos-hid-inspector/`: SwiftPM tool. `scan` and `watch` use IOHID to see
  what macOS receives below browsers/games. `feedback` writes encrypted BLE
  correction packets via CoreBluetooth, mirroring the Python sender. `companion`
  combines `watch` + `feedback` on one run loop and prints a combined verdict;
  add `--report path.json` to persist a versioned snapshot for compatibility
  matrices. `selftest` round-trips `FeedbackCrypto` and the report JSON without
  touching Bluetooth. The `BluetrackHostKit` library target holds the shared
  crypto contract.
- `docs/GAMEPAD_DEBUGGING.md`: host-side gamepad debugging workflow.

## Current Input Truth

Touchpad smoothness is a good baseline. Do not casually retune touch constants
or pacer behavior. Current design:

- touch events enqueue motion only;
- an 8 ms pacer drains motion;
- HID sending is decoupled from the pacer;
- mouse deltas coalesce during Bluetooth stalls;
- UI status/telemetry is throttled so Compose does not compete with input;
- short touch delivery gaps are predicted and then reconciled against real input.

Diagnostics use:

```bash
adb logcat -s BluetrackInput Bluetrack
```

`touch gap` can remain visible because Android really delayed touch delivery.
Focus on whether `pacer` and `queue` stay low and whether the user says the
cursor feels smooth.

## Current Gamepad Truth

The current gamepad report is 7 bytes:

```text
[0] buttons low
[1] buttons high
[2] hat switch, neutral = 8
[3] left X
[4] left Y
[5] right X
[6] right Y
```

The descriptor advertises Game Pad, 16 buttons, neutral hat switch/D-pad, and
four signed 8-bit axes. Browser Gamepad API pages often require an actual button
press or axis gesture after the page is open, so Bluetrack sends an automatic
visible wake train in gamepad mode rather than a too-short down/up pulse. It also
sends a rate-limited discovery wake at the start of Gamepad touch gestures so a
browser page opened after mode activation can still enumerate the controller.
The discovery wake uses a high-numbered button instead of Button 1 to reduce
accidental in-game actions.

On tested macOS hardware, the host may show the Bluetooth device as the phone
name, such as `aiwaki`, with primary usage `Mouse`. The same IOHID device still
contains the Game Pad collection and report 2. Use:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector scan --name Bluetrack --no-bluetooth
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector watch --name Bluetrack --seconds 15 --no-bluetooth --no-elements
```

After any descriptor change, macOS usually needs one host-side "Forget This
Device" and fresh pairing because it caches HID report maps.

## Non-Negotiables

- Keep HID and BLE feedback mentally separate.
- Keep hardware failures explicit and actionable.
- Do not unregister/re-register HID when switching mouse/gamepad modes; the app
  uses one composite descriptor.
- Do not auto-connect bonded audio/accessory devices.
- Do not regress the touchpad baseline unless real logs justify it.
- Add focused JVM tests for pure Kotlin behavior.
- Keep CI green before asking the user to test.
