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
  writes a 128-byte payload `eph_x25519(32) || id_ed25519(32) ||
  ed25519_sig_over_eph(64)`, then reads the phone's 32-byte X25519
  public key. The peripheral verifies the Ed25519 signature against
  the embedded identity pubkey and TOFU-pins the identity on the first
  successful handshake (subsequent handshakes from a different
  identity are rejected even if the pin is correct).
- feedback `4846ff87-f2d4-4df2-9500-9bf8ed23f9e6` (WRITE_NO_RESPONSE) —
  28-byte authenticated frames.

Per-session key derivation:

```text
shared    = X25519(local_priv, peer_pub)        # 32 bytes
key       = HKDF-SHA256(shared, salt, info_key, 32)
salt8     = HKDF-SHA256(shared, salt, info_salt, 8)
salt      = "bluetrack-feedback-v1"
info_key  = "aes-256-gcm key+nonce-salt|pin:<digits>"
info_salt = "aes-256-gcm key+nonce-salt|pin:<digits>|nonce-salt"
```

The `<digits>` are a fresh 6-digit pairing pin the peripheral generates
per `BleHidGateway.startGatt` and shows on the `Pin` status row. The
host CLI requires `--pin <digits>` (Python: `--pin`); a host that does
not know the pin derives different key material and AES-GCM tag
verification fails on every frame. Pin validation accepts 4–12 ASCII
digits.

Frame shape (28 bytes):

```text
[0..3]    counter_le              (uint32 LE, AES-GCM nonce suffix)
[4..11]   AES-256-GCM ciphertext  (Float32 dx, Float32 dy LE)
[12..27]  AES-256-GCM 16-byte tag
nonce     = salt8 || counter_le   (12 bytes)
```

The phone rotates its X25519 keypair (and pairing pin) on every
`BleHidGateway.startGatt` call, so each app launch / reconnect produces
fresh forward-secret key material. The receiver enforces a 64-frame
sliding replay window after AES-GCM verification (IPsec ESP / SRTP
style): exact replays, counters older than 63 below the highest
accepted, and counter wrap-around (`0xFFFFFFFF → 0`) are dropped even
though the cryptographic tag is valid. The host MUST rotate the
session before counter wrap — 2³² packets ≈ 248 days at 5 ms cadence.

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
- Feedback crypto: per-session X25519 ECDH + HKDF-SHA256 + AES-256-GCM,
  with a 6-digit pairing pin mixed into HKDF AND a long-term Ed25519
  host identity TOFU-pinned on the phone. Pin gates the cryptographic
  derivation; identity gates *which* host the phone will talk to even
  if the pin is leaked. Identity pinning has a TOFU window — anyone
  who completes the *very first* handshake is trusted forever (or
  until "Forget host"). The host private key is stored unencrypted
  under `~/.config/bluetrack-hid-inspector{,-py}/host_identity_v1.json`
  with mode `0600`; an attacker with read access to the file can
  impersonate the host.
- Replay window: 64-frame sliding window on the receiver. Replays,
  out-of-order frames more than 63 below the high-water counter, and
  counter wrap-around are silently dropped. Host MUST rotate sessions
  before 2³² packets.
- Identity backup/migration: both CLIs ship `export-identity --to
  <path>` and `import-identity --from <path>` subcommands. The
  Swift host inspector stores its identity under
  `~/.config/bluetrack-hid-inspector/host_identity_v1.json` and the
  Python sender under `~/.config/bluetrack-hid-inspector-py/...`;
  both files share the same byte-shape (`{"private_key_b64": "..."}`)
  so an identity exported from one CLI can be imported by the other
  without translation. `import-identity` validates the source seed
  before touching the destination and preserves the previous
  identity at `<destination>.bak` for one-shot recovery from a
  mistaken import.
- Release-build crypto: `android/app/proguard-rules.pro` keeps the
  BouncyCastle X25519/Ed25519 packages and every Bluetrack BLE
  protocol entry class. Android CI runs `assembleRelease` (R8
  minify enabled) and greps the resulting `app-release-unsigned.apk`
  for the expected symbols on every PR, so a future R8 / library
  upgrade cannot silently strip the handshake.
- Cross-platform golden vectors: `host/test-vectors/feedback_v1.json`
  is the deterministic fixture every platform's tests load and
  byte-compare against. Regenerate via
  `python3 host/test-vectors/generate_vectors.py` after any protocol
  change; the diff is part of the PR review. Note: Swift's CryptoKit
  Ed25519 is hedged (non-deterministic), so Swift verifies the
  fixture's signature without comparing sig bytes — Android (BC) and
  Python (cryptography) Ed25519 are RFC 8032 deterministic and do
  compare byte-for-byte.
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

- Close the TOFU window: out-of-band identity exchange (QR code on
  first run) so the very first handshake is also authenticated. Today
  anyone who guesses the pin within the first reconnect window can
  pin themselves as the trusted host.
- Encrypt the host identity at rest. Currently the Ed25519 private key
  sits in `~/.config/bluetrack-hid-inspector{,-py}/host_identity_v1.json`
  with mode `0600`. macOS Keychain (Swift) and `~/.config` umask leak
  scenarios both deserve a follow-up.
- Bind the handshake signature to a peripheral nonce so replayed
  `(eph, id, sig)` triples cannot occupy the peripheral's session slot
  (currently a slot-hijack is possible but harmless — attacker without
  the corresponding ephemeral private cannot send valid frames, so the
  session times out silently).

## User Preference

The user wants Codex/Claude to be proactive and is comfortable granting
broad control when the direction is clear. Be decisive, but keep
evidence visible: build/test results, PR links, and concrete hardware
caveats matter here.
