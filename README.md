# Bluetrack Pro Engine

![Android CI](https://github.com/aiwaki/bluetrack/actions/workflows/android-ci.yml/badge.svg)
![Host CI](https://github.com/aiwaki/bluetrack/actions/workflows/host-ci.yml/badge.svg)
![Platform](https://img.shields.io/badge/platform-Android%2029%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Kotlin%20%2B%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Crypto](https://img.shields.io/badge/crypto-X25519%20%2B%20Ed25519%20%2B%20AES--256--GCM-0A66C2)

Bluetrack Pro Engine is a native Android/Kotlin prototype that turns an
Android phone into a low-latency Bluetooth HID input device (mouse +
gamepad) with a separate authenticated BLE side channel for host-side
correction packets. The phone is the input device; the computer is the
HID host.

The target product feel is automatic and calm: no manual buttons in
normal flow, clear state, smooth input that hides platform jitter
honestly, and security defaults that are realistic for a one-user
diagnostic tool.

## Status

Active prototype. The Android app is a native Compose UI with a
Bluetooth HID device gateway, an advertised BLE feedback service that
performs a per-session ECDH handshake with TOFU host pinning, and a
small Python reference sender for host-side integration tests. A
companion macOS SwiftPM CLI (`bluetrack-hid-inspector`) verifies HID
delivery below the browser Gamepad API and drives the encrypted feedback
channel from the host side.

Per-PR history lives in `CHANGELOG.md`. Future Codex/agent sessions
should start with `AGENTS.md` and `docs/CODEX_CONTEXT.md`. Claude
sessions can start with `CLAUDE.md` and `.claude/rules/`.

## Architecture

Two Bluetooth paths, deliberately separate.

### HID path

1. Touchpad / gamepad input on Android enqueues motion or button events.
2. `MainViewModel` drains accumulated deltas every 8 ms.
3. `TranslationEngine` produces HID mouse or gamepad reports (composite
   descriptor; mode switching never re-registers the HID app).
4. `HidOutputBuffer` decouples Bluetooth sending from the input pacer.
5. `HidTransportGovernor` backs off when `sendReport` shows backpressure.
6. The Bluetooth host receives the reports as if from a real mouse or
   gamepad.

### Feedback path

A separate authenticated BLE GATT service (UUID
`0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263`) accepts encrypted correction
packets from the host. Two characteristics:

- **Handshake** (`4846ff88-…`, READ + WRITE, 128 bytes). Host writes
  `eph_x25519(32) || id_ed25519(32) || ed25519_sig_over_eph(64)`. Phone
  verifies the Ed25519 signature, TOFU-pins the identity public key on
  first use, then reads the phone's 32-byte X25519 public key.
- **Feedback** (`4846ff87-…`, WRITE_NO_RESPONSE, 28 bytes). Frame:
  `counter(4 LE) || AES-256-GCM ciphertext(8) || GCM tag(16)`.

Per-session key derivation:

```text
shared    = X25519(local_priv, peer_pub)
key       = HKDF-SHA256(shared, salt, "aes-256-gcm key+nonce-salt|pin:<digits>", 32)
nonceSalt = HKDF-SHA256(shared, salt, "<info>|nonce-salt", 8)
salt      = "bluetrack-feedback-v1"
nonce     = nonceSalt(8) || counter_LE(4)
```

Pin (4–12 ASCII digits) is shown on the phone status row; rotates per
`BleHidGateway.startGatt`. Receiver enforces a 64-frame sliding replay
window after AES-GCM verify. Per-peer handshake rate limit (4 capacity,
4 tokens/s) drops floods before crypto.

See `docs/THREAT_MODEL.md` for the full attack surface table and
residual risks.

## Features

- Native Android app — Kotlin, Coroutines, StateFlow, Jetpack Compose.
- Composite HID descriptor (mouse + gamepad), mode toggle without
  re-registration.
- Per-session X25519 ECDH + HKDF-SHA256 + AES-256-GCM on the BLE
  feedback channel.
- 6-digit pairing pin mixed into HKDF; pin shown on phone status row.
- TOFU host identity pinning (Ed25519). "Forget host" action wipes the
  pin so the next handshake re-pairs.
- 64-frame sliding replay window on the receiver.
- Per-peer handshake rate limiter (token bucket) drops floods before
  crypto.
- Persistent lifetime counters (HID reports / feedback packets /
  rejections) survive process kill.
- Connectable BLE advertising for host-side feedback discovery.
- Touchpad input pipeline with fractional-delta preservation, 8 ms
  pacer, transport governor, predictive gap-filler.
- Auto-connect to computer-class hosts; AirPods / pointing devices /
  keyboards explicitly ignored.
- macOS host inspector (`bluetrack-hid-inspector`): `scan`, `watch`,
  `feedback`, `companion`, `selftest`, `export-identity`,
  `import-identity`.
- Python reference sender (`ble_encrypt_sender.py`) with the same
  protocol contract; identity export/import compatible with the Swift
  CLI.
- Cross-platform golden-vector fixture
  (`host/test-vectors/feedback_v1.json`); CI byte-equal across Swift,
  Android, Python.
- ProGuard release smoke check on every PR (R8 minify + class-survival
  grep).
- Fuzz tests on the handshake parser and the feedback decrypt path.
- Two CI lanes: Android (Ubuntu) + Host (macOS).

## Build

### Requirements

- JDK 17
- Android SDK 34
- Android Studio or command-line Android SDK tools
- Xcode 15+ for the macOS host CLI (`swift build` ≥ Swift 5.10)
- Python 3.10+ with `bleak` and `cryptography` for the BLE sender

### Command line

Android:

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

If `JAVA_HOME` is unset on macOS but Android Studio is installed, point
Gradle at the bundled JBR for the current shell:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Host CLI:

```bash
swift test --package-path host/macos-hid-inspector
swift build --package-path host/macos-hid-inspector
```

Cross-platform golden vectors (run after any protocol change):

```bash
python3 host/test-vectors/generate_vectors.py
python3 host/test-vectors/test_golden_vectors.py
```

### Android Studio

Open the `android/` directory as the project root, let Gradle sync
finish, and run the `app` configuration on an Android 10+ device with
Bluetooth support.

## Pairing and runtime

1. Install and open the app on an Android device that supports the
   Bluetooth HID Device profile.
2. Grant the nearby-devices Bluetooth permission and the
   foreground-service notification when prompted.
3. Accept Android's Bluetooth-enable and discoverability sheets if they
   appear. Bluetrack opens the pairing window automatically when no
   bonded host exists.
4. On the PC, open Bluetooth settings and add `Bluetrack Pro Engine` as
   a HID input device.
5. Return to the app. Bluetrack keeps a foreground HID keep-alive
   service running, refreshes the compatibility snapshot, and
   auto-connects to a bonded computer-class host when possible.
6. Drag inside the input surface or move a mouse / trackpad connected
   to the Android device. Frames flow.

The status row carries six fields: Bluetooth state, HID profile state,
pairing state, BLE feedback state, the current 6-digit `Pin` (when the
GATT server is open), and the `Trust` fingerprint of the pinned host
identity (with a Forget button next to it).

If a PC does not see the phone, check the rows. `HID profile
unavailable` means the Android firmware does not expose the HID Device
profile to third-party apps. `Feedback advertising failed` means BLE
advertising is unavailable, but HID may still pair. A bonded phone that
never reaches `Connected` is paired at the Bluetooth level but not yet
connected as a HID host. Some hosts show the device under the phone
name rather than `Bluetrack Pro Engine`; what matters is that the HID
service underneath is connected. After a descriptor change, forget and
re-pair the device once so the host caches the new descriptor.
Bluetrack ignores bonded audio / accessory devices (AirPods,
headphones, speakers, keyboards, mice, trackpads) when picking an
auto-connect host.

Gamepad mode sends controller-style HID reports, so it does not move
the macOS cursor. Bluetrack exposes 16 buttons, a hat switch / D-pad
(neutral = 8), and four axes. A visible automatic wake train fires when
gamepad mode connects, and a rate-limited discovery wake fires at the
start of Gamepad-mode touch gestures so browser testers, games, and
emulators see a real "user gesture" after the page is focused.

On macOS, use the host inspector to separate raw HID delivery from
browser Gamepad API behaviour:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    scan --name Bluetrack --no-bluetooth

swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    watch --name Bluetrack --seconds 15 --no-bluetooth --no-elements
```

For the full BLE feedback path (HID watch + encrypted writes on one run
loop) run the companion mode with the pin from the phone:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    companion --pin 246810 --seconds 15 --report host/snapshots/<file>.json
```

See `docs/GAMEPAD_DEBUGGING.md` for interpretation tips.

Touchpad input preserves fractional motion and coalesced historical
samples before HID quantisation. UI touch callbacks only enqueue
motion; a background 8 ms input pacer drains accumulated deltas into
HID reports so the host receives steady timing instead of bursty touch
batches. High-rate counters and telemetry are throttled before reaching
Compose so the diagnostic UI does not compete with touch delivery
during active movement. HID transport is also decoupled from the
pacer: deltas are coalesced in a small output buffer and sent from a
dedicated sender so short Bluetooth stalls do not stop the input
clock. When the Android Bluetooth stack shows backpressure the sender
briefly lowers its catch-up rate so it can coalesce more motion
instead of hammering `sendReport` into another stall. The touchpad
also predicts very short gaps between Android touch events and
reconciles that predicted motion against the next real event, so the
cursor moves through small touch-delivery holes without long drift.

Inspect input diagnostics during hardware testing:

```bash
adb logcat -s BluetrackInput Bluetrack
```

## Python BLE sender

```bash
python -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install bleak cryptography

# Send encrypted feedback frames. Pin is displayed on the phone status
# row when the BLE GATT server is open.
python android/tools/ble_encrypt_sender.py --pin 246810

# Pin a known BLE address instead of scanning for the service UUID.
python android/tools/ble_encrypt_sender.py --pin 246810 \
    --address 00:11:22:33:44:55
```

The sender stores its long-term Ed25519 host identity at
`~/.config/bluetrack-hid-inspector-py/host_identity_v1.json` and
prints its fingerprint on every run for comparison with the phone's
`Trust` row. Identity helpers:

```bash
# Back up the active identity (file format compatible with the Swift
# CLI; same JSON shape).
python android/tools/ble_encrypt_sender.py export-identity \
    --to /tmp/bluetrack-identity.json

# Restore from a backup. The previous identity is preserved at
# <destination>.bak so a mistaken import is reversible.
python android/tools/ble_encrypt_sender.py import-identity \
    --from /tmp/bluetrack-identity.json

# Roll the identity (next handshake will need Forget host on the phone).
python android/tools/ble_encrypt_sender.py --pin 246810 \
    --reset-host-identity
```

The Swift host inspector mirrors all three (`export-identity --to`,
`import-identity --from`, `--reset-host-identity`).

## Security notes

- BLE feedback path: per-session X25519 ECDH + HKDF-SHA256 +
  AES-256-GCM, 6-digit pairing pin mixed into HKDF, TOFU-pinned
  Ed25519 host identity, 64-frame sliding replay window, per-peer
  handshake rate limit. Cryptographic primitives come from Apple
  CryptoKit (Swift), BouncyCastle (Android), and `cryptography`
  (Python); each is verified byte-equal against the cross-platform
  golden vector fixture on every CI run.
- Pin is short-lived but plain on the phone screen. Identity TOFU
  prevents a leaked pin from spoofing future sessions, but the *very
  first* handshake after a Forget is whoever speaks first. Out-of-band
  identity exchange (QR on first run) is on the roadmap.
- Host private key is plaintext JSON at `~/.config/.../host_identity_v1.json`
  with mode 0600. Anyone with shell access to the host machine can
  copy it and impersonate. Roadmap: macOS Keychain (Swift) and a
  better store on Python.
- Bluetooth pairing security is whatever the OS negotiates; Bluetrack
  does not strengthen or weaken the underlying pairing layer.
- Read `docs/THREAT_MODEL.md` for the adversary tier table, the
  attack-surface mitigations, the residual risks (TOFU window,
  slot-hijack via replayed handshake triples, pin shoulder-surf,
  library side-channel trust), and the explicit out-of-scope list.

## Repository layout

```text
android/app/src/main/kotlin/dev/xd/bluetrack/   Android app source
android/app/src/test/kotlin/dev/xd/bluetrack/   JVM unit tests (incl. fuzz + golden-vector)
android/tools/                                  Host-side BLE helper scripts
host/macos-hid-inspector/                       macOS IOHID inspection / feedback CLI (SwiftPM)
host/test-vectors/                              Cross-platform golden-vector fixture + tests
host/snapshots/                                 Hardware compatibility matrix (per `companion --report`)
docs/CODEX_CONTEXT.md                           Long-form mental model + hardware validation
docs/THREAT_MODEL.md                            Adversaries, attack surfaces, residual risks
docs/PROJECT_AUDIT.md                           Blind-spots audit + keyboard HID spec
docs/UI_DESIGN.md / UI_BRIEF.md / UI_BRIEF_GAPS*.md
                                                Redesign hand-off (UI work paused)
.github/workflows/android-ci.yml                Android lane (Ubuntu)
.github/workflows/host-ci.yml                   Host lane (macOS)
CHANGELOG.md                                    Per-PR history since v2.0.0
```
