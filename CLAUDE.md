# CLAUDE.md: Bluetrack Project Context

Auto-loaded by Claude Code at session start. Pair with `AGENTS.md` (Codex
operating prompt + roadmap) and `docs/CODEX_CONTEXT.md` (mental model +
hardware validation script). Project rules live in
`.claude/rules/00-project.md` and auto-load with this file.

## What Bluetrack Is

Native Android/Kotlin app that turns an Android phone into a Bluetooth HID
input device (mouse + gamepad) for a PC, with a separate BLE GATT server
for encrypted correction packets ("feedback" channel). Phone is the HID
device; computer is the host.

The product feel target is automatic and calm: no manual buttons in
normal flow, clear state, automatic pairing/connect where Android allows,
and smooth input that hides platform jitter when it can do so honestly.

## System Model

Two Bluetooth paths, kept separate everywhere:

- **HID path.** Android registers `BluetoothHidDevice` with a composite
  mouse + gamepad descriptor. Hardware/firmware-dependent: if Android does
  not expose `BluetoothProfile.HID_DEVICE`, the app cannot force it.
- **Feedback path.** Android runs a BLE GATT server for encrypted
  correction writes. This does not make the phone appear as a HID device.

## Orient at session start

```bash
git status -sb
git log --oneline --decorate -5
gh pr list --json number,title,url,state,isDraft
```

Toolchain on this machine: Android Studio bundled JBR, Xcode for the host
CLI tests:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Primary validation:

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug && cd ..
swift test --package-path host/macos-hid-inspector
python3 -m py_compile android/tools/ble_encrypt_sender.py
```

Debug APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Important Files

- `android/app/src/main/kotlin/dev/xd/bluetrack/MainActivity.kt`: Compose
  UI, permission prompts, Bluetooth readiness flow, touchpad surface.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/BleHidGateway.kt`: HID
  registration, host auto-connect, descriptor, feedback GATT server.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/GatewayStatusReducer.kt`:
  pure-Kotlin status/event reducer used by `BleHidGateway.updateStatus`.
  Side effects (logcat, StateFlow write) stay in the gateway; reducer is
  covered by JVM tests in `GatewayStatusReducerTest`.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/PayloadDecryptor.kt`:
  AES-128-CTR feedback frame decoding.
- `android/app/src/main/kotlin/dev/xd/bluetrack/engine/TranslationEngine.kt`:
  mouse and gamepad HID report generation.
- `android/app/src/main/kotlin/dev/xd/bluetrack/engine/GamepadReportFormat.kt`:
  current 7-byte gamepad report shape.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ui/HidOutputBuffer.kt`:
  decoupled HID transport buffer.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ui/HidTransportGovernor.kt`:
  adaptive sender backpressure governor.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ui/TouchMotionPredictor.kt`:
  short-horizon touch gap filler with debt reconciliation.
- `host/macos-hid-inspector/`: SwiftPM tool. `scan` / `watch` use IOHID;
  `feedback` writes encrypted BLE packets via CoreBluetooth; `companion`
  runs both on one run loop and prints a combined PASS/FAIL verdict.
  `--report path.json` works on `watch`/`feedback`/`companion`.
  `selftest` is a no-Bluetooth runtime smoke check. The `BluetrackHostKit`
  library target owns the shared crypto and report schema; canonical
  assertions live in `Tests/BluetrackHostKitTests`.
- `host/snapshots/`: hardware compatibility matrix, one JSON per
  `companion --report` run.
- `docs/GAMEPAD_DEBUGGING.md`: host-side gamepad debugging workflow,
  including the phone-named composite case macOS exhibits on this Mac.
