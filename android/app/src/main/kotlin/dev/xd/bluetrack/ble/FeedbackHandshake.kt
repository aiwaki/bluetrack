package dev.xd.bluetrack.ble

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest

/**
 * 128-byte payload the host writes to the handshake characteristic.
 *
 * Wire format mirrors the Swift `FeedbackHandshakePayload`:
 * ```
 * [0..32)    eph_x25519_public_key   (X25519 ephemeral, per session)
 * [32..64)   id_ed25519_public_key   (Ed25519 long-term host identity)
 * [64..128)  signature               (Ed25519 over eph_x25519_public_key)
 * ```
 *
 * The peripheral parses the payload, verifies the Ed25519 signature
 * against the embedded identity public key, and (after pin-based HKDF
 * succeeds) pins the identity public key on first use. Subsequent
 * handshakes that present a different identity are rejected even if the
 * pin is correct.
 */
data class FeedbackHandshakePayload(
    val ephemeralPublicKey: ByteArray,
    val identityPublicKey: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(ephemeralPublicKey.size == FeedbackSession.PUBLIC_KEY_SIZE)
        require(identityPublicKey.size == IDENTITY_PUBLIC_KEY_SIZE)
        require(signature.size == IDENTITY_SIGNATURE_SIZE)
    }

    /** Verify the Ed25519 signature over `ephemeralPublicKey`. */
    fun verifySignature(): Boolean = try {
        Ed25519.verify(
            signature,
            0,
            identityPublicKey,
            0,
            ephemeralPublicKey,
            0,
            ephemeralPublicKey.size,
        )
    } catch (_: Throwable) {
        false
    }

    /** Stable short fingerprint of `identityPublicKey` — first 16 hex chars
     *  of SHA-256(pub). Matches the Swift `HostIdentity.fingerprint`. */
    fun identityFingerprint(): String = fingerprintOf(identityPublicKey)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackHandshakePayload) return false
        return ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
            identityPublicKey.contentEquals(other.identityPublicKey) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.contentHashCode()
        result = 31 * result + identityPublicKey.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        const val IDENTITY_PUBLIC_KEY_SIZE: Int = 32
        const val IDENTITY_SIGNATURE_SIZE: Int = 64
        const val WIRE_SIZE: Int =
            FeedbackSession.PUBLIC_KEY_SIZE +
                IDENTITY_PUBLIC_KEY_SIZE +
                IDENTITY_SIGNATURE_SIZE // 128

        /** Parse exactly 128 bytes. Returns null on length mismatch. */
        fun parse(bytes: ByteArray): FeedbackHandshakePayload? {
            if (bytes.size != WIRE_SIZE) return null
            val eph = bytes.copyOfRange(0, FeedbackSession.PUBLIC_KEY_SIZE)
            val idStart = FeedbackSession.PUBLIC_KEY_SIZE
            val idEnd = idStart + IDENTITY_PUBLIC_KEY_SIZE
            val id = bytes.copyOfRange(idStart, idEnd)
            val sig = bytes.copyOfRange(idEnd, idEnd + IDENTITY_SIGNATURE_SIZE)
            return FeedbackHandshakePayload(
                ephemeralPublicKey = eph,
                identityPublicKey = id,
                signature = sig,
            )
        }

        fun fingerprintOf(identityPublicKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(identityPublicKey)
            val sb = StringBuilder(64)
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            return sb.substring(0, 16)
        }
    }
}
