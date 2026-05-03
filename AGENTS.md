# Bluetrack Agent Brief

Start every new coding session here, then read `docs/CODEX_CONTEXT.md`.

## Project Shape

Bluetrack is a native Android/Kotlin prototype that turns an Android device into
a Bluetooth HID mouse/gamepad bridge with an encrypted BLE feedback channel.
The Android app is the input device. The PC is the Bluetooth host.

Current work is on branch `codex/harden-android-build-tests` and draft PR #3:
`https://github.com/aiwaki/bluetrack/pull/3`.

## First Commands

```bash
git status -sb
git log --oneline --decorate -5
gh pr list --head "$(git branch --show-current)" --json number,title,url,state,isDraft
```

Use Android Studio's bundled runtime on this machine:

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

## Code Map

- `android/app/src/main/kotlin/dev/xd/bluetrack/MainActivity.kt`: Compose UI,
  Bluetooth permission/enable/discoverability flows, diagnostic surface.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/BleHidGateway.kt`: HID
  device registration, host connection status, feedback GATT server, BLE
  advertising.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/PayloadDecryptor.kt`:
  AES-128-CTR feedback frame decoding.
- `android/app/src/main/kotlin/dev/xd/bluetrack/engine/TranslationEngine.kt`:
  mouse/gamepad HID report generation and correction application.
- `android/tools/ble_encrypt_sender.py`: host-side reference sender for
  encrypted feedback packets.

## Non-Negotiables

- Do not assume Bluetooth HID Device support exists on every Android device.
  If unavailable, the app should say so clearly.
- Keep the UX diagnostic and honest. Hardware-dependent failures are expected;
  hiding them makes the project worse.
- Preserve the Android/Kotlin native stack. The old Flutter-style artifacts were
  intentionally removed.
- Use explicit file staging. Avoid sweeping unrelated local changes into PRs.
- Prefer focused tests around translation, crypto packet handling, and stateful
  Bluetooth edge cases.

## Expert Operating Prompt

Use this prompt as the house style for future Bluetrack work:

```text
Act as a senior Android/Bluetooth engineer and product-minded diagnostic-tool
designer. Start by reading AGENTS.md and docs/CODEX_CONTEXT.md, then inspect
the current git/PR state before editing. Treat Bluetooth HID and BLE feedback
as separate systems. Make the app honest about hardware capabilities, visible
state, and failure reasons. Prefer small, verifiable changes that improve real
device debugging. Build and test with Android Studio's bundled JBR, update the
project memory when the operating model changes, and push through the existing
draft PR with CI green.
```

## Current Hardware Truth

The app can request Android discoverability and register a HID Device app, but
the actual pairing path depends on firmware support for `BluetoothProfile.HID_DEVICE`.
The feedback channel is separate BLE GATT/advertising work; it does not make the
phone appear as a classic Bluetooth HID device by itself. The app now drives the
Bluetooth enable, discoverability, compatibility refresh, and bonded-host HID
connect flow automatically where Android allows it, including a quiet foreground
maintenance loop for HID reconnect attempts. The HID host picker must ignore
bonded audio/accessory devices such as AirPods and should only auto-connect
computer-class or computer-named hosts. Bluetooth ownership is application-scoped
and backed by a foreground keep-alive service so minimizing the Activity does not
intentionally unregister HID. Android's own confirmation dialogs still cannot be
skipped, and Android requires an ongoing notification for the background
keep-alive service.

## Good Next Bets

- Test the cockpit on real Android hardware and record compatibility snapshots.
- Extract a testable status reducer from `BleHidGateway` and add JVM tests.
- Build a host companion utility that verifies both paths: HID pairing plus BLE
  feedback writes.
- Improve UI density and controls once the hardware path is verified.
