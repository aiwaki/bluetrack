# Bluetrack Pro Engine — Ultra-Low Latency Android MITM HID Controller

![Platform](https://img.shields.io/badge/Platform-Android%2029%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Crypto](https://img.shields.io/badge/Crypto-AES--128--CTR-0A66C2)
![Python](https://img.shields.io/badge/Python-BLE%20Sender-3776AB?logo=python&logoColor=white)

Bluetrack Pro Engine is a native Android Kotlin system for ultra-low latency mouse-input interception, HID translation, and encrypted BLE feedback processing for competitive tuning workflows.

---

## Architecture Overview

### Primary Data Path
1. **Raw Mouse Input (USB OTG)**
2. **Android Interception Layer** (`OnGenericMotionListener` via Compose bridge)
3. **Translation Engine** (relative mouse deltas → HID reports)
4. **BLE HID Output** to host

### Feedback Loop
1. **PC-side analysis/tuning loop** produces correction vectors (`dx`, `dy`)
2. **AES-128-CTR encrypted packet** sent over BLE GATT (12-byte frame)
3. **Android decrypts** packet and injects correction into next motion frame

---

## Features

- **Native Android architecture** (Kotlin + Coroutines + StateFlow + Compose)
- **Jetpack Compose diagnostic UI** with modern cyberpunk styling
- **Dual HID modes**: Native Mouse / Gamepad Emulation
- **Mouse-to-joystick aim-assist translation pipeline**
- **Zero-allocation hot-path design** for reduced GC jitter in motion/decryption paths
- **AES-128-CTR encrypted BLE GATT feedback channel** (12-byte payload format)

---

## Getting Started (Android)

### Requirements
- Android Studio (latest stable)
- Android SDK 34
- JDK 17

### Open & Build
1. Clone the repository.
2. Open **`android/`** as the project root in Android Studio.
3. Let Gradle sync finish.
4. Build app module:
   - Android Studio: **Build > Make Project**
   - or CLI from repo root (if wrapper exists):
     - `cd android`
     - `./gradlew :app:assembleDebug`

### Run
- Connect an Android device (API 29+) with OTG + Bluetooth support.
- Install and launch the app from Android Studio.

---

## Getting Started (Python)

Reference sender script:
- `android/tools/ble_encrypt_sender.py`

### Requirements
- Python 3.10+
- BLE adapter on host machine

### Install dependencies
```bash
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install --upgrade pip
pip install bleak cryptography
```

### Configure and run
1. Edit `DEVICE_ADDRESS` and `CHAR_UUID` in `android/tools/ble_encrypt_sender.py`.
2. Run:
```bash
python android/tools/ble_encrypt_sender.py
```

---

## Security Notes

- Current key/salt are static for integration bring-up and must be rotated/replaced for production.
- Use per-session key exchange and authenticated transport in hardened deployments.

---

## Repository Layout

- `android/app/src/main/kotlin/dev/xd/bluetrack/` — Android app source
- `android/tools/` — host-side tooling/scripts (Python BLE sender)

