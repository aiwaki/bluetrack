# Bluetrack Project Rules

Single source of truth for Bluetrack-specific Claude rules. Was four files
(`01-project-principles.md`, `02-android-build-and-tests.md`,
`03-bluetooth-hid-rules.md`, `04-input-smoothness-rules.md`); collapsed
to reduce auto-load overhead at session start.

## Principles

- Treat Bluetrack as a native Android/Kotlin Bluetooth HID project, not a
  web or Flutter app.
- The phone is the Bluetooth HID device. The computer is the HID host.
- Keep HID and BLE feedback separate in architecture, UI wording, tests,
  and debugging.
- The target product feel is automatic and calm: no normal-flow manual
  buttons, clear state, graceful recovery where Android allows it.
- Hardware-dependent failures are normal. Make them visible and
  actionable instead of hiding or pretending they are software-only.
- Preserve the current smooth touchpad baseline unless fresh hardware
  logs show exactly why it must change.
- Descriptor changes require host-side forget/re-pair during validation
  because macOS and other hosts cache HID report maps.

## Android build and tests

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android && ./gradlew testDebugUnitTest assembleDebug && cd ..
python3 -m py_compile android/tools/ble_encrypt_sender.py
```

- Keep JVM tests focused on pure Kotlin behavior: report formatting,
  transport buffering, touch prediction, crypto packet handling, host
  classification, state reducers.
- Stage explicit paths. Do not stage broad unrelated files.
- Re-check GitHub Actions after pushing to an open PR (Android lane on
  Ubuntu, Host lane on macOS).
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Bluetooth HID

- `BleHidGateway` owns Android `BluetoothHidDevice` registration, host
  auto-connect, report IDs, and the BLE feedback GATT server.
- Mode switching must not call `unregisterApp()`. Use the composite
  descriptor and switch only the active report path.
- Never auto-connect bonded audio/accessory devices (AirPods, headphones,
  speakers, keyboards, mice, trackpads). Prefer computer-class or
  computer-named hosts.
- Android system prompts for Bluetooth enable, discoverability, pairing,
  and the foreground-service notification cannot be bypassed by app
  code.
- Current gamepad report format:

```text
[0] buttons low
[1] buttons high
[2] hat switch, neutral = 8
[3] left X
[4] left Y
[5] right X
[6] right Y
```

- Hosts may show Bluetrack as the Android phone name. The HID service
  underneath still exposes the composite mouse/gamepad report map.
- Browser Gamepad API pages usually need a real button or axis gesture
  after the page is open. Keep the automatic visible gamepad wake train
  and the rate-limited touch-gesture discovery wake unless real hardware
  evidence shows a better activation strategy.
- Use `host/macos-hid-inspector` before changing descriptors. If macOS
  receives report 2, the Android HID path is alive and the issue is
  browser/GameController activation or host mapping.
- After descriptor changes, tell testers to forget and re-pair the host
  device.

## BLE feedback channel

- Protocol: per-session X25519 ECDH + HKDF-SHA256 + AES-256-GCM,
  6-digit pairing pin mixed into HKDF, TOFU-pinned Ed25519 host
  identity, 64-frame sliding replay window, per-peer handshake rate
  limit (token bucket), persistent lifetime counters that survive
  process kill.
- Three implementations (Swift `BluetrackHostKit`, Android
  `FeedbackSession`, Python `ble_encrypt_sender.py`) MUST stay
  byte-identical at the wire. Cross-platform golden vectors at
  `host/test-vectors/feedback_v1.json` are checked on every CI run;
  any protocol change requires regenerating that fixture and
  diffing it in the PR.
- Host CryptoKit Ed25519 is hedged (non-deterministic per Apple
  design); Android (BC) and Python (`cryptography`) Ed25519 follow
  RFC 8032. Tests on Swift compare signatures via
  `verifySignature()`, never byte-equal; Android + Python do byte-equal.
- Any change to the handshake characteristic shape, the HKDF info
  bytes, or the AES frame layout is a forced-re-pair release. Bundle
  with other forced-re-pair changes (next: keyboard HID + battery
  descriptor) to avoid forcing the user through two re-pairings in a
  row. Flag in CHANGELOG.md.
- Threat model: read `docs/THREAT_MODEL.md` before any security PR.
  TOFU window on first handshake, pin shoulder-surfing, slot-hijack
  via replayed handshake triples, library side-channel trust are
  documented residual risks; do not "fix" them silently.

## Input smoothness

- Touchpad smoothness is a hardware-tested baseline. Do not casually
  retune constants.
- Pipeline:
  - Android touch events enqueue motion.
  - `MainViewModel` drains accumulated deltas every 8 ms.
  - `TranslationEngine` preserves fractional mouse deltas.
  - `HidOutputBuffer` decouples Bluetooth sending from the input pacer.
  - `HidTransportGovernor` backs off after measured `sendReport` stalls.
  - `TouchMotionPredictor` fills short touch-delivery gaps and reconciles
    predicted motion against real touch motion.
- Do not block the input pacer on Bluetooth calls, Compose state updates,
  logging, or compatibility refresh work.
- Debug input: `adb logcat -s BluetrackInput Bluetrack`. Log meanings:
  - `touch gap`: Android delayed touch delivery.
  - `pacer gap`: app's input clock stalled.
  - `queue latency`: ViewModel motion queue lagged.
  - `output queue latency`: HID transport backed up.
  - `HID send`: Android/Bluetooth `sendReport` blocked.
- Prefer small, measured changes over speculative smoothing.
