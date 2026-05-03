# Bluetrack Pro Engine

![Android CI](https://github.com/aiwaki/bluetrack/actions/workflows/android-ci.yml/badge.svg)
![Platform](https://img.shields.io/badge/platform-Android%2029%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Kotlin%20%2B%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Crypto](https://img.shields.io/badge/crypto-AES--128--CTR-0A66C2)

Bluetrack Pro Engine is a native Android/Kotlin prototype for low-latency mouse
input capture, HID report translation, and encrypted BLE feedback experiments.
The project is aimed at accessibility tooling, input-device research, and
calibration workflows where deterministic input handling matters.

## Status

This repository is an active prototype. The Android app contains a native
Compose diagnostic UI, a Bluetooth HID device gateway, an advertised AES-CTR
BLE feedback service, and a small Python reference sender for host-side
integration tests.

Future Codex/agent sessions should start with `AGENTS.md` and
`docs/CODEX_CONTEXT.md`; those files carry the current project memory,
hardware caveats, validation commands, and roadmap.

## Architecture

### Input Path

1. Raw mouse movement is captured on Android through a Compose-hosted motion
   listener.
2. `TranslationEngine` converts relative mouse deltas into HID report bytes.
3. `BleHidGateway` sends the report to the connected Bluetooth HID host.

The app acts as the input device. The PC acts as the Bluetooth host and must
pair with the Android device while the app is open.

### Feedback Path

1. A host-side calibration loop computes a correction vector.
2. The correction is encrypted as an AES-128-CTR BLE frame:
   `[0..3]=counter_le`, `[4..11]=ciphertext(float dx, float dy)`.
3. Android advertises the feedback service UUID, accepts writes to the feedback
   characteristic, decrypts the frame, and applies the correction to the next
   input reports.

## Features

- Native Android app built with Kotlin, Coroutines, StateFlow, and Jetpack Compose.
- Bluetooth HID mouse and gamepad report descriptors with explicit report IDs.
- In-app cockpit with Bluetooth enable, pairing/discoverability, HID, host,
  input, compatibility, timeline, and feedback service status.
- Touchpad input surface for hardware checks without an external mouse.
- Connectable BLE advertising for host-side feedback discovery.
- AES-128-CTR compatible BLE feedback decoder.
- JVM unit tests for packet decryption and HID report formatting.
- GitHub Actions workflow for Android unit tests and debug APK assembly.
- Python sender script that can scan for the Bluetrack feedback service and send
  encrypted correction packets.

## Build

### Requirements

- JDK 17
- Android SDK 34
- Android Studio or command-line Android SDK tools

### Command Line

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

If the shell cannot find Java but Android Studio is installed on macOS, point
Gradle at Android Studio's bundled runtime for the current terminal session:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Android Studio

Open the `android/` directory as the project root, let Gradle sync finish, and
run the `app` configuration on an Android 10+ device with Bluetooth support.

## Pairing and Runtime

1. Install and open the app on an Android device that supports the Bluetooth HID
   Device profile.
2. Grant the nearby-devices Bluetooth permissions when prompted.
3. Accept Android's Bluetooth enable and discoverability prompts if they appear.
   Bluetrack opens the pairing window automatically when no bonded host exists.
4. On the PC, open Bluetooth settings and add `Bluetrack Pro Engine` as a mouse
   or gamepad-class input device.
5. Return to the app. Bluetrack will keep a foreground HID keep-alive service
   running, refresh compatibility, and auto-connect a bonded host when possible.
6. Keep the app open or backgrounded and drag inside the input surface, or move a mouse
   or trackpad connected to the Android device.

If the PC does not show the phone, check the app status rows. `HID profile
unavailable` means the Android device firmware does not expose the HID Device
profile to third-party apps. `Feedback advertising failed` means the BLE
feedback channel could not be advertised, but HID pairing may still work. A
bonded phone that never reaches `Connected` is paired at the Bluetooth level but
not connected as a HID host yet. If you upgraded from an older mouse-only build,
forget the old Bluetooth device once and pair again so the host caches the new
composite mouse/gamepad descriptor. Bluetrack ignores bonded audio/accessory
devices such as AirPods, headphones, speakers, keyboards, mice, and trackpads
when choosing an automatic HID host.

Gamepad mode sends controller-style HID reports, so it will not move the macOS
cursor. Bluetrack exposes a gamepad usage with 16 buttons and four axes, then
sends a short button wake pulse when gamepad mode connects or receives first
input so browser testers, games, and emulators are more likely to enumerate it.
After this descriptor change, forget and re-pair `Bluetrack Pro Engine` once if
the host still has the older mouse/gamepad descriptor cached.

Touchpad input preserves fractional motion and coalesced historical touch
samples before HID quantization. UI touch callbacks only enqueue motion; a
background 8 ms input pacer drains accumulated deltas into HID reports so the
host receives steadier timing instead of bursty touch-event batches. High-rate
HID report counters and telemetry are throttled before reaching Compose so the
diagnostic UI does not compete with touch delivery during active movement.
HID transport is also decoupled from the pacer: mouse deltas are coalesced in a
small output buffer and sent from a dedicated sender so short Bluetooth stalls
do not stop the input clock. When Android's Bluetooth stack shows backpressure,
the sender briefly lowers its catch-up rate so it can coalesce more mouse motion
instead of hammering `sendReport` into another stall.
Hidden input diagnostics log only when a threshold is crossed, without changing
motion behavior. During hardware testing, inspect them with:

```bash
adb logcat -s BluetrackInput Bluetrack
```

## Python BLE Sender

Reference tool:

```text
android/tools/ble_encrypt_sender.py
```

Install and run:

```bash
python -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install bleak cryptography
python android/tools/ble_encrypt_sender.py
```

By default the sender scans for the advertised Bluetrack feedback service. You
can also pass a known BLE address:

```bash
python android/tools/ble_encrypt_sender.py --address 00:11:22:33:44:55
```

## Security Notes

- The current key and salt are static prototype values.
- Production use should replace them with per-session key negotiation and
  authenticated transport.
- Treat BLE write access as trusted only after explicit pairing and validation.

## Repository Layout

```text
android/app/src/main/kotlin/dev/xd/bluetrack/  Android app source
android/app/src/test/kotlin/dev/xd/bluetrack/  JVM unit tests
android/tools/                                      Host-side BLE helper tools
.github/workflows/android-ci.yml                    Android CI workflow
```
