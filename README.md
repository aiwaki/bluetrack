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
Compose diagnostic UI, a Bluetooth HID device gateway, an AES-CTR BLE feedback
decoder, and a small Python reference sender for host-side integration tests.

## Architecture

### Input Path

1. Raw mouse movement is captured on Android through a Compose-hosted motion
   listener.
2. `TranslationEngine` converts relative mouse deltas into HID report bytes.
3. `BleHidGateway` sends the report to the connected Bluetooth HID host.

### Feedback Path

1. A host-side calibration loop computes a correction vector.
2. The correction is encrypted as an AES-128-CTR BLE frame:
   `[0..3]=counter_le`, `[4..11]=ciphertext(float dx, float dy)`.
3. Android decrypts the frame and applies the correction to the next input
   reports.

## Features

- Native Android app built with Kotlin, Coroutines, StateFlow, and Jetpack Compose.
- Bluetooth HID mouse and gamepad report descriptors with explicit report IDs.
- AES-128-CTR compatible BLE feedback decoder.
- JVM unit tests for packet decryption and HID report formatting.
- GitHub Actions workflow for Android unit tests and debug APK assembly.
- Python sender script for encrypted BLE packet generation.

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

### Android Studio

Open the `android/` directory as the project root, let Gradle sync finish, and
run the `app` configuration on an Android 10+ device with Bluetooth support.

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

Before running, set `DEVICE_ADDRESS` and `CHAR_UUID` in the sender script.

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
