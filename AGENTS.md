# Bluetrack Agent Brief

Start every new coding session here, then read `docs/CODEX_CONTEXT.md`.

## Project Shape

Bluetrack is a native Android/Kotlin prototype that turns an Android device into
a Bluetooth HID mouse/gamepad bridge with an encrypted BLE feedback channel.
The Android app is the input device. The PC is the Bluetooth host.

PR #3 was merged into `main`. Current follow-up work may be on
`codex/add-macos-hid-inspector` while gamepad visibility is verified with a
host-side macOS IOHID inspector.

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
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/GatewayStatusReducer.kt`:
  pure-Kotlin reducer that produces the next `GatewayStatus` plus an optional
  `GatewayEvent`; the gateway only owns the StateFlow write and the logcat
  emit. Tests live in `GatewayStatusReducerTest`.
- `android/app/src/main/kotlin/dev/xd/bluetrack/ble/PayloadDecryptor.kt`:
  AES-128-CTR feedback frame decoding.
- `android/app/src/main/kotlin/dev/xd/bluetrack/engine/TranslationEngine.kt`:
  mouse/gamepad HID report generation and correction application.
- `android/tools/ble_encrypt_sender.py`: host-side reference sender for
  encrypted feedback packets.
- `host/macos-hid-inspector/`: SwiftPM tool with four subcommands.
  - `scan` / `watch` use IOHID to enumerate the Bluetrack composite HID and
    print live input values.
  - `feedback` uses CoreBluetooth to scan for the BLE feedback service,
    connect, and write AES-128-CTR encrypted `(counter, dx, dy)` packets.
  - `companion` runs `watch` and `feedback` together on a single run loop
    and prints a combined PASS/FAIL verdict for both paths. Add
    `--report path.json` to persist the verdict, exit codes, event counts,
    peripheral identity, and timings as a versioned JSON snapshot.
  - `selftest` round-trips `FeedbackCrypto` without Bluetooth (works on
    machines with only CommandLineTools installed).
  - The `BluetrackHostKit` library target owns the shared crypto contract.
- `docs/GAMEPAD_DEBUGGING.md`: host-side gamepad debugging workflow.

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
keep-alive service. Pointer input is paced: touch callbacks enqueue deltas,
while `MainViewModel` drains them every 8 ms into `TranslationEngine` to avoid
UI-thread HID bursts. Historical touch samples are batched once per Android
motion event, and high-rate report counters/telemetry are throttled before
Compose sees them so the UI does not starve touch delivery. HID transport is
decoupled from the pacer with a small output buffer and a dedicated sender; mouse
deltas coalesce there instead of being dropped during short Bluetooth stalls. The
sender has a small adaptive governor that briefly slows catch-up sends after
measured `sendReport` backpressure. Touchpad movement also uses a short-horizon
predictor that fills small Android touch-delivery gaps and reconciles predicted
motion against the next real touch event to avoid long drift. The current
gamepad report is 7 bytes: 16 buttons, a neutral hat switch/D-pad, and four
signed 8-bit axes. Gamepad activation sends a visible automatic button wake
train so browser Gamepad API pages can catch a real gamepad gesture, and
Gamepad touch gestures send a rate-limited discovery wake if the page opened
after mode activation. macOS may show the device as the phone name with primary
usage Mouse while still exposing Game Pad report 2; use
`host/macos-hid-inspector` to distinguish raw HID delivery from browser
Gamepad API activation/mapping. Descriptor changes still require one host-side
forget/re-pair.
Hidden input diagnostics log rare touch, pacer, queue, output-queue, and
HID-send threshold crossings under `BluetrackInput`; keep this diagnostic layer
passive unless hardware evidence says what to change.

## Good Next Bets

- Test the cockpit on real Android hardware and record compatibility snapshots.
- Build a host companion utility that verifies both paths: HID pairing plus BLE
  feedback writes.
- Keep extending the macOS HID inspector into a cross-platform host validation
  companion.
- Improve UI density and controls once the hardware path is verified.
