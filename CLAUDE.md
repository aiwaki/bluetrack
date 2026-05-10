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
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/FeedbackSession.kt`:
  per-session BLE feedback crypto. X25519 ECDH (BouncyCastle) +
  HKDF-SHA256 with the pairing pin in the info bytes + AES-256-GCM
  on 28-byte frames. Sliding 64-frame replay window enforced by
  `acceptCounter`.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/FeedbackHandshake.kt`:
  128-byte handshake payload `eph_x25519 || id_ed25519 || sig` with
  Ed25519 signature verification.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/PayloadDecryptor.kt`:
  thin wrapper over `FeedbackSession` + `TrustedHostPolicy`. Owns
  `installHandshake(bytes)` (parse + verify + TOFU-pin + derive
  session) and `decryptPayloadTo(bytes, callback)` for incoming
  AES-256-GCM feedback frames.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/TrustedHostStore.kt`:
  `TrustedHostPolicy` interface + SharedPreferences-backed
  `TrustedHostStore` + `InMemoryTrustedHostPolicy` (test seam) for
  TOFU host identity pinning.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/HandshakeRateLimiter.kt`:
  per-peer token-bucket (4 capacity, 4 tokens/s, LRU-capped at 64
  peers) that drops handshake floods before any crypto runs.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/LifetimeCountersStore.kt`
  + `LifetimeCountersAccumulator.kt`: SharedPreferences-backed
  lifetime totals (HID reports / feedback packets / rejections) with
  throttled writes (every 50 events) and synchronous flush on
  rejections so a process kill never loses one.
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
- `host/test-vectors/`: cross-platform golden-vector fixture
  (`feedback_v1.json`) + Python generator + Python test. Swift,
  Android, and Python tests all byte-compare against this fixture.
  Regenerate via `python3 host/test-vectors/generate_vectors.py`
  after any protocol change; CI `git diff --exit-code`s the result.
- `docs/GAMEPAD_DEBUGGING.md`: host-side gamepad debugging workflow,
  including the phone-named composite case macOS exhibits on this Mac.
- `docs/THREAT_MODEL.md`: adversary tiers, attack-surface mitigations,
  residual risks, out-of-scope. Reference before any security PR.
- `CHANGELOG.md`: per-PR history since v2.0.0, with forced-re-pair
  PRs explicitly flagged.
