package dev.xd.bluetrack.ble

/**
 * Thin wrapper around [FeedbackSession] that the GATT server uses to:
 *  - expose the local 32-byte X25519 public key for handshake reads,
 *  - install the host-supplied peer public key (handshake write), and
 *  - decrypt incoming 28-byte AES-256-GCM feedback frames.
 *
 * Each `BleHidGateway.startGatt()` call replaces the active session, so
 * key material is forward-secret across app launches/reconnects.
 */
class PayloadDecryptor {

    private var session: FeedbackSession = FeedbackSession()

    /**
     * Local 32-byte X25519 public key. The GATT server returns this to the
     * host on handshake characteristic reads.
     */
    val publicKey: ByteArray
        get() = session.publicKey

    val isSessionReady: Boolean
        get() = session.isReady

    /**
     * Replace the active session with a fresh ephemeral keypair. Call when
     * a new GATT server lifecycle begins to drop any prior session state.
     */
    fun rotateSession() {
        session = FeedbackSession()
    }

    /**
     * Install the host's public key and derive the AES-256-GCM session.
     * Returns true on success; false if the input is the wrong length or
     * derivation throws (only [IllegalArgumentException] is expected).
     */
    fun installPeerPublicKey(peerPublicKey: ByteArray): Boolean {
        return try {
            session.deriveSession(peerPublicKey)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Decrypt a 28-byte feedback frame; invokes [onDecrypted] exactly once
     * on success. Returns false for malformed frames, unauthenticated
     * frames, or before the session is ready.
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
}
