# Bluetrack — threat model

One-page anchor for security thinking. Read alongside
`docs/CODEX_CONTEXT.md` (which describes what the protocol does) and
`host/test-vectors/feedback_v1.json` (which freezes the byte layout).
This file describes what the protocol defends against, what it does
not, and which classes of attack are explicitly out of scope for the
current prototype.

## Assets

In approximate order of value:

1. **HID input integrity to the paired host.** The phone sends mouse,
   gamepad, and (planned) keyboard reports to a paired computer that
   trusts them. A successful injection attack drives the user's
   computer.
2. **The pinned host identity on the phone.** Once an Ed25519 host
   identity is TOFU-pinned, only that identity can derive the AES
   session. Compromise of the pinning store lets the attacker
   silently re-pair.
3. **The host's long-term Ed25519 private key.** Stored at
   `~/.config/bluetrack-hid-inspector{,-py}/host_identity_v1.json`
   mode 0600 in plaintext. Compromise lets the attacker impersonate
   the host indefinitely until the user notices the wrong
   fingerprint and taps "Forget host".
4. **Per-session ephemeral key material.** X25519 ephemeral keys
   exist only for the lifetime of one BLE GATT server cycle and one
   host-side connection. Compromise reveals only that one session's
   feedback frames.
5. **The pairing pin.** Six ASCII digits, regenerated on every
   `BleHidGateway.startGatt`. Visible on the phone screen for the
   duration of one session.
6. **Feedback frame contents (`dx`, `dy`).** Two `Float32`s of
   correction data per frame. Low value individually; in aggregate
   they expose a coarse picture of how the host is correcting the
   phone's input.

The Bluetooth HID transport itself is symmetric to any other
Bluetooth Classic HID device — Bluetrack does not promise stronger
properties than the underlying pairing.

## Adversaries

| Tier | Adversary                              | Capability                                                                                |
|------|----------------------------------------|-------------------------------------------------------------------------------------------|
| A    | Passive sniffer in BT range            | Captures GATT traffic. Does not transmit.                                                 |
| B    | Active in-range attacker               | Tier A + can connect to the BLE GATT server, write to characteristics, replay packets.    |
| C    | Shoulder-surfer / camera               | Reads the pin off the phone screen.                                                       |
| D    | Physical access to the host machine    | Reads / writes `~/.config/.../host_identity_v1.json`, observes CLI banners, runs the CLI. |
| E    | Malicious paired host                  | Already trusted at the Bluetooth pairing layer. Sends malformed BLE feedback writes.      |
| F    | Compromised Android app                | Has access to SharedPreferences and the BLE GATT server callbacks.                        |

The current design treats Tier A and Tier B as the primary threat —
"someone in BT range trying to drive the user's computer." Tier C
through F are partially addressed; see the residual-risks section.

## Attack surfaces and mitigations

### S1. BLE GATT server

`BleHidGateway` exposes the feedback service with two characteristics
on the phone. Any BLE central in range can connect.

- **Mitigation: per-session X25519 ECDH** + HKDF-SHA256 + AES-256-GCM
  on every feedback frame. A passive sniffer (Tier A) sees ciphertext
  it cannot decrypt without one of the X25519 private keys.
- **Mitigation: pin in HKDF.** A Tier B attacker who connects but
  does not know the pin derives a different AES key; AES-GCM tag
  verification fails on the first frame. Pin entropy ≈ 20 bits, but
  it rotates per session, so brute force is "one attempt per
  reconnect" and is observable on the phone (rejection counter
  climbs, a row appears in the timeline).
- **Mitigation: TOFU host identity binding.** Pin alone does not
  authenticate *who* connected. The phone pins the first Ed25519
  identity it sees and refuses different identities thereafter,
  even if they know the pin. A Tier B attacker who learns the pin
  later still cannot inject feedback unless they also have the
  host's identity private key (Tier D).
- **Mitigation: replay window.** AES-GCM rejects nonce reuse at the
  cryptographic layer; on top of that the receiver enforces a
  64-frame sliding window. Captured authenticated frames cannot be
  replayed later in the session, and counter wrap-around is
  rejected.

### S2. Handshake characteristic

The 128-byte payload `eph_x25519 || id_ed25519 || sig` is written by
the host before any feedback frames flow.

- **Mitigation: Ed25519 signature.** Verified on every handshake.
  A Tier B attacker who attempts to substitute their own identity
  while keeping a captured `eph` and `sig` will fail verification.
- **Mitigation: malformed inputs return `MALFORMED` /
  `BAD_SIGNATURE` outcomes** without crashing the GATT callback.
  Fuzzing the parser is a planned follow-up (`engineering/fuzz`).

### S3. Pairing pin display

The pin is rendered as plain text on the Bluetrack status row while
the GATT server is open.

- **Tier C exposure.** Anyone who can see the phone screen learns
  the pin. The pin is short-lived (one session), and even if leaked
  it does not bypass identity TOFU. Combined exposure (pin + Tier B)
  is required to spoof a connection on the *first* run; after the
  first valid handshake the pin no longer authorises new identities.
- **Future mitigation.** Blur the pin until tapped (planned).

### S4. Host identity at rest

The Ed25519 private key lives in a plaintext JSON file with mode
0600 in `~/.config`.

- **Tier D exposure.** Anyone with shell access to the host machine
  can copy the file and impersonate the host indefinitely. There
  is no detection for this on the phone — the cloned host produces
  a valid handshake the phone has already pinned.
- **Future mitigations.** macOS Keychain on Swift, encrypted-at-rest
  storage on Python (see `docs/PROJECT_AUDIT.md` §1B and the
  roadmap in `AGENTS.md`).

### S5. Bluetooth pairing layer

Standard Android Bluetooth Classic + BLE pairing. Bluetrack does
not strengthen or weaken this layer.

- **Inherits Bluetooth pairing security.** A device that the user
  has not paired cannot use HID. A Tier B attacker without pairing
  cannot inject HID reports; the encrypted feedback channel is
  separate and gated by the protocol above.

### S6. Cross-platform implementation drift

Three implementations of the same protocol (Swift, Android, Python).

- **Mitigation: golden vectors.** `host/test-vectors/feedback_v1.json`
  is regenerated on every CI run and diffed against the committed
  copy; all three platforms run byte-equal tests against it. Any
  drift fails CI on the next PR.

## Residual risks (intentional)

- **TOFU window on the very first handshake.** Anyone — including a
  Tier B attacker who happens to be present — can be the first
  identity the phone pins. Defence is in scope of the planned OOB
  identity exchange (QR on first run); see roadmap.
- **Slot-hijack DoS via replayed `(eph, id, sig)` triples.** The
  signature commits identity to the ephemeral key but not to a
  peripheral nonce, so a replayed handshake occupies the
  peripheral's session slot until the BLE deadline elapses. The
  attacker cannot send valid frames (no ephemeral private), so the
  slot times out silently. Acceptable today; mitigation is to bind
  the signature to a peripheral-supplied nonce.
- **Pin shoulder-surfing.** Pin is plain text on the screen. Mitigated
  partially by TOFU (a stolen pin alone is not enough after first
  pairing). Not addressed for the first-pairing case.
- **Side channels in the cryptographic implementations.** Apple
  CryptoKit (Swift), BouncyCastle X25519/Ed25519 (Android), and
  `cryptography` (Python) are all out-of-the-box; we do not run
  constant-time analysis against them. Standard library trust.
- **No Bluetooth-side encryption beyond what the host pair
  negotiates.** The HID input path has no application-layer
  encryption. The user's computer trusts whatever HID device it
  paired with; the only defence is the user not pairing strange
  devices.

## Out of scope (explicitly)

- **Nation-state adversaries.** A determined attacker with TPM-class
  resources is not in scope.
- **Multi-user phones.** The Android app assumes the phone has a
  single primary user.
- **Hostile Android apps with root.** Anything with root on the
  phone bypasses every storage protection. Out of scope.
- **Physical compromise of the phone.** Ditto — out of scope.
- **Dispute resolution / non-repudiation.** Bluetrack does not log
  signed audit trails. The activity timeline is local-only and
  forgettable.

## Detection / response

- **Phone-side timeline.** Every handshake outcome
  (`OK / MALFORMED / BAD_SIGNATURE / UNTRUSTED_HOST /
  DERIVATION_FAILED`) and every rejected feedback packet adds an
  entry to the gateway timeline. The user sees them on the Hub.
- **Untrusted host attempt.** Surfaces as a separate timeline entry
  ("Untrusted host attempted handshake. Tap Forget host to
  re-pair."). The planned UI calls this out persistently.
- **Counter / replay drops.** Tracked in the rejected-feedback
  counter; the planned Diagnostics screen will break it down by
  reason.

## Future work (security-flavoured)

Sourced from `docs/CODEX_CONTEXT.md` and `docs/PROJECT_AUDIT.md`:

- OOB identity exchange (QR code) to close the TOFU window.
- Identity at rest: macOS Keychain (Swift), better Python storage.
- Peripheral nonce in handshake signature (closes the slot-hijack
  DoS).
- Pin blur + reveal-on-tap on the phone screen.
- Anti-DoS rate limit on handshake writes.
- Fuzz testing of `FeedbackHandshakePayload.parse` +
  `installHandshake`.
- Threat model for the keyboard HID extension once it lands.
