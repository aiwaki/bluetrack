package dev.xd.bluetrack.ble

/**
 * Thin wrapper around [FeedbackSession] + [TrustedHostStore] that the
 * GATT server uses to:
 *  - expose the local 32-byte X25519 public key for handshake reads,
 *  - parse and verify the host-supplied 128-byte handshake (eph X25519 +
 *    Ed25519 identity + Ed25519 signature) and TOFU-pin the identity, and
 *  - decrypt incoming 28-byte AES-256-GCM feedback frames.
 *
 * The active pairing pin is provided by `BleHidGateway.startGatt()` and
 * mixed into the AES-GCM key derivation so a host that does not know the
 * pin produces non-decryptable frames. Each `rotateSession(pin)` call
 * replaces both the ephemeral keypair and the pin, so key material is
 * forward-secret across app launches/reconnects and across pin rolls.
 *
 * Identity binding is independent of the pin: a host that knows the pin
 * but presents a different Ed25519 identity than the one the peripheral
 * pinned (TOFU) is rejected with [HandshakeOutcome.UNTRUSTED_HOST].
 */
class PayloadDecryptor(
    private val trustedHosts: TrustedHostPolicy,
) {

    /**
     * Outcome of [installHandshake] — surfaced so the GATT server can
     * decide which status message to push and whether to send a
     * `GATT_FAILURE` response on the handshake characteristic.
     */
    enum class HandshakeOutcome {
        OK,
        MALFORMED,
        BAD_SIGNATURE,
        UNTRUSTED_HOST,
        DERIVATION_FAILED,
    }

    private var session: FeedbackSession = FeedbackSession()
    private var activePin: String = DEFAULT_PIN

    /**
     * Local 32-byte X25519 public key. The GATT server returns this to the
     * host on handshake characteristic reads.
     */
    val publicKey: ByteArray
        get() = session.publicKey

    val isSessionReady: Boolean
        get() = session.isReady

    /**
     * Replace the active session with a fresh ephemeral keypair and the
     * supplied pin. The pin is held in memory until the next rotation;
     * `installHandshake` uses it to derive the AES-256-GCM session.
     *
     * Throws [IllegalArgumentException] if [pin] does not satisfy
     * `FeedbackSession.normalizedPinBytes`.
     */
    fun rotateSession(pin: String) {
        require(FeedbackSession.normalizedPinBytes(pin) != null) {
            "pin must be ${FeedbackSession.PIN_MIN_LENGTH}.." +
                "${FeedbackSession.PIN_MAX_LENGTH} ASCII digits"
        }
        session = FeedbackSession()
        activePin = pin
    }

    /**
     * Parse and verify a 128-byte handshake payload, TOFU-pin the host
     * identity, and derive the AES-256-GCM session against the embedded
     * ephemeral X25519 public key. Returns one of [HandshakeOutcome].
     */
    fun installHandshake(handshakeBytes: ByteArray): HandshakeOutcome {
        val parsed = FeedbackHandshakePayload.parse(handshakeBytes)
            ?: return HandshakeOutcome.MALFORMED
        if (!parsed.verifySignature()) {
            return HandshakeOutcome.BAD_SIGNATURE
        }
        if (!trustedHosts.acceptOrPin(parsed.identityPublicKey)) {
            return HandshakeOutcome.UNTRUSTED_HOST
        }
        return try {
            session.deriveSession(parsed.ephemeralPublicKey, activePin)
            HandshakeOutcome.OK
        } catch (_: IllegalArgumentException) {
            HandshakeOutcome.DERIVATION_FAILED
        }
    }

    /**
     * Decrypt a 28-byte feedback frame; invokes [onDecrypted] exactly once
     * on success. Returns false for malformed frames, unauthenticated
     * frames, replays, or before the session is ready.
     */
    fun decryptPayloadTo(bleData: ByteArray, onDecrypted: (Float, Float) -> Unit): Boolean {
        return session.decryptPayloadTo(bleData, onDecrypted)
    }

    /**
     * Convenience API when allocations are acceptable.
     */
    fun decryptPayload(bleData: ByteArray): Pair<Float, Float>? {
        var x = 0f
        var y = 0f
        var ok = false
        if (decryptPayloadTo(bleData) { dx, dy ->
                x = dx
                y = dy
                ok = true
            }
        ) {
            // ok set inside callback
        }
        return if (ok) Pair(x, y) else null
    }

    private companion object {
        // Sentinel pin used before the first rotateSession; ensures
        // installHandshake still derives a valid (but unused) session
        // even if a host writes its pubkey before the gateway rotates.
        // BleHidGateway always rotates with a real random pin in startGatt
        // so the sentinel never reaches the wire under normal flow.
        private const val DEFAULT_PIN = "00000000"
    }
}
