package dev.xd.bluetrack.ble

/**
 * Reason a single feedback channel write was rejected. Used to
 * split the lifetime rejection counter into the seven buckets
 * the canvas Diagnostics route surfaces (`docs/design/v1/
 * diagnostics.jsx`).
 *
 * Mapping to the gateway code paths:
 *
 *  - [Size]            — frame is not exactly 28 bytes (12 nonce
 *    + 12 ciphertext + 16 tag). Caught in [PayloadDecryptor.
 *    decryptPayloadTo] before the AES-GCM verifier runs.
 *  - [Gcm]             — AES-GCM tag failed to verify. Tampered
 *    payload, wrong session key (stale PIN / wrong host), or
 *    truncated frame.
 *  - [Replay]          — counter outside the 64-frame sliding
 *    window. Either a real replay or a benign reordering.
 *  - [HandshakeLength] — handshake frame is not the canonical
 *    128 bytes (`eph_x25519 || id_ed25519 || sig`).
 *  - [Signature]       — Ed25519 signature on the handshake
 *    fails — host identity does not match the claimed pubkey.
 *  - [Untrusted]       — handshake is well-formed and signed by
 *    a real identity but the identity does not match the
 *    TOFU-pinned host.
 *  - [X25519]          — peer X25519 pubkey is malformed enough
 *    that ECDH cannot derive a shared secret.
 *  - [RateLimit]       — per-peer token bucket dropped the
 *    handshake before any crypto work was attempted.
 *
 * Persisted by [LifetimeCountersStore] as its own
 * `lifetime_rejections_<name>` key so the eight buckets survive
 * a process kill.
 */
enum class RejectionCause {
    Size,
    Gcm,
    Replay,
    HandshakeLength,
    Signature,
    Untrusted,
    X25519,
    RateLimit,

    /**
     * Frame arrived before the AES-256-GCM session was derived
     * (host wrote feedback bytes before completing the
     * handshake). Distinct from [Size] / [Gcm] because the
     * frame was the right shape — there just was no key yet.
     * Added in step 9b fixup after Codex review flagged that
     * lumping these into [Size] inflated the "Wrong frame size"
     * bucket during normal pre-handshake activity.
     */
    SessionNotReady,
}
