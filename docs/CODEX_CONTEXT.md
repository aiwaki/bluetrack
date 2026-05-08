# Codex Context: Bluetrack Pro Engine

Long-form mental model + hardware validation script + roadmap. The
project shape, code map, and operating prompt live in `CLAUDE.md` and
`AGENTS.md`; project rules live in `.claude/rules/00-project.md`. Read
those first.

## North Star

Bluetrack is an Android-native input translation lab:

- Capture relative mouse/trackpad movement on Android.
- Translate it into Bluetooth HID mouse or gamepad reports.
- Pair the Android device with a PC as the Bluetooth HID input device.
- Accept encrypted BLE feedback packets from the host to adjust output.

The useful product feeling is not a marketing page. It is a reliable
diagnostic tool that tells the operator exactly which Bluetooth layer is
alive, blocked, or unsupported.

## Mental Model

Two Bluetooth paths. Keep them separate in your head and in the UI.

### HID path

Android registers as a HID Device using `BluetoothHidDevice`. A PC pairs
to it as the host. If the firmware does not expose
`BluetoothProfile.HID_DEVICE` to apps, HID will not work on that device.

Owner: `android/app/src/main/kotlin/dev/xd/bluetrack/ble/BleHidGateway.kt`.

### Feedback path

Android also runs a BLE GATT server for encrypted host feedback.
Service UUID `0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263` exposes two
characteristics:

- handshake `4846ff88-f2d4-4df2-9500-9bf8ed23f9e6` (READ + WRITE) — host
  writes its 32-byte X25519 public key, then reads the phone's 32-byte
  public key.
- feedback `4846ff87-f2d4-4df2-9500-9bf8ed23f9e6` (WRITE_NO_RESPONSE) —
  28-byte authenticated frames.

Per-session key derivation:

```text
shared    = X25519(local_priv, peer_pub)        # 32 bytes
key       = HKDF-SHA256(shared, salt, info_key, 32)
salt8     = HKDF-SHA256(shared, salt, info_salt, 8)
salt      = "bluetrack-feedback-v1"
info_key  = "aes-256-gcm key+nonce-salt"
info_salt = "aes-256-gcm key+nonce-salt|nonce-salt"
```

Frame shape (28 bytes):

```text
[0..3]    counter_le              (uint32 LE, AES-GCM nonce suffix)
[4..11]   AES-256-GCM ciphertext  (Float32 dx, Float32 dy LE)
[12..27]  AES-256-GCM 16-byte tag
nonce     = salt8 || counter_le   (12 bytes)
```

The phone rotates its X25519 keypair on every `BleHidGateway.startGatt`
call, so each app launch / reconnect produces fresh forward-secret key
material. Counter wrap (2³² packets ≈ 248 days at 5 ms cadence) requires
a session rotation; the peripheral rejects duplicate (key, counter)
pairs via tag failure.

This BLE path is not what makes the phone show up as a mouse/gamepad on
the PC — it is the calibration/correction side channel.

## Hardware Validation Script

On Android:

1. Install the debug APK
   (`android/app/build/outputs/apk/debug/app-debug.apk`).
2. Open Bluetrack and grant Bluetooth nearby-device permissions.
3. Confirm the status rows:
   - `HID ready (...)` is required for HID pairing.
   - `HID profile unavailable` means the device/firmware cannot run this
     HID path as a third-party app.
   - `Advertising feedback service` means the BLE correction channel is
     visible.
4. Accept the Android discoverability prompt.
5. After pairing/bonding, return to the app. It should run the HID
   keep-alive service, refresh compatibility, and auto-connect the bonded
   host. Minimize the Activity to verify HID host remains connected.

On PC:

1. Open Bluetooth settings, add a new device.
2. Pair with `Bluetrack Pro Engine` if it appears, or with the
   phone-named entry that exposes the HID service (see
   `docs/GAMEPAD_DEBUGGING.md`).
3. Keep the Android app foregrounded.
4. Move a mouse/trackpad connected to Android or drag inside the app's
   input surface.

After descriptor changes, macOS may need "Forget This Device" + fresh
pairing to drop the cached HID report map. Subsequent mode switching
should not require reconnecting.

Gamepad-mode verification: in a browser gamepad tester or game.
Inspector help:

```bash
swift run --package-path host/macos-hid-inspector bluetrack-hid-inspector \
    companion --seconds 15 --report host/snapshots/<file>.json
```

If report 2 events arrive on the IOHID side, Android HID delivery is
working and any remaining issue is browser Gamepad API activation or
host game-controller mapping.

Feedback-only sender:

```bash
python android/tools/ble_encrypt_sender.py [--address 00:11:22:33:44:55]
```

Input jank diagnostics:

```bash
adb logcat -s BluetrackInput Bluetrack
```

## Known Limitations

- HID Device profile support is device/firmware-dependent.
- The diagnostic touchpad is not yet a polished product surface.
- Auto-connect prefers computer-class hosts; ignores audio/accessory
  bonded devices (AirPods, headphones, keyboards, mice, trackpads).
- Android system confirmation dialogs for enable/discoverability/pairing
  cannot be bypassed by app code.
- Android requires an ongoing foreground-service notification for the
  HID keep-alive path.
- Mode switching never calls `unregisterApp()`; one composite descriptor
  is registered and only the active report path changes.
- Feedback crypto: per-session X25519 ECDH + HKDF-SHA256 + AES-256-GCM.
  No long-term identity, no peer authentication beyond handshake
  characteristic exclusivity. A man-in-the-middle that intercepts the
  initial handshake can substitute its own pubkey.
- Runtime Bluetooth validation still needs real Android hardware plus a
  PC.

## Roadmap

High leverage:

- Capture more `host/snapshots/` entries on different Mac+phone combos
  (especially with the phone in Gamepad mode at capture time).

UX:

- Refine the diagnostic touchpad into a more deliberate control surface
  once the new UI artifacts land.

Security:

- Authenticate the X25519 handshake against MitM (e.g. host pubkey
  pinning, short-authentication-string verification, or device-bound
  identity keys exchanged out-of-band).
- Add replay window detection on the peripheral (currently any
  in-session counter that hasn't been used will authenticate; counter
  monotonicity is not enforced).

## User Preference

The user wants Codex/Claude to be proactive and is comfortable granting
broad control when the direction is clear. Be decisive, but keep
evidence visible: build/test results, PR links, and concrete hardware
caveats matter here.
