package dev.xd.bluetrack.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * Per-connection BLE feedback session. Each side generates an ephemeral
 * X25519 key pair, exchanges the 32-byte public keys over the handshake
 * characteristic, and derives a 32-byte AES-256-GCM key + 8-byte nonce
 * salt via HKDF-SHA256 over the X25519 shared secret.
 *
 * Frame layout on the feedback characteristic (28 bytes):
 *
 * ```
 * [0..3]    counter (uint32, little-endian)
 * [4..11]   AES-256-GCM ciphertext of (Float32 dx, Float32 dy) little-endian
 * [12..27]  AES-256-GCM 16-byte authentication tag
 * ```
 *
 * The AES-GCM nonce is `NONCE_SALT || counter_LE` (8 + 4 bytes). The host
 * must use a unique counter per packet within a session; the peripheral
 * rejects duplicate counters via tag failure (different nonces with the
 * same key still authenticate as long as the counter is unique).
 */
class FeedbackSession {

    companion object {
        const val PUBLIC_KEY_SIZE: Int = 32
        const val PRIVATE_KEY_SIZE: Int = 32
        const val FRAME_SIZE: Int = 28
        const val COUNTER_PREFIX_SIZE: Int = 4
        const val PLAINTEXT_SIZE: Int = 8
        const val TAG_SIZE: Int = 16
        const val NONCE_SALT_SIZE: Int = 8
        private const val GCM_TAG_BITS: Int = 128

        // Domain-separated HKDF salt for this protocol version.
        private val HKDF_SALT: ByteArray = "bluetrack-feedback-v1".toByteArray()
        private val HKDF_INFO_KEY: ByteArray =
            "aes-256-gcm key+nonce-salt".toByteArray()
        private val HKDF_INFO_SALT: ByteArray =
            ("aes-256-gcm key+nonce-salt".toByteArray()
                + "|nonce-salt".toByteArray())
    }

    private val privateKey = ByteArray(PRIVATE_KEY_SIZE)
    val publicKey: ByteArray = ByteArray(PUBLIC_KEY_SIZE)

    private var symmetricKey: SecretKeySpec? = null
    private var nonceSalt: ByteArray? = null

    init {
        SecureRandom().nextBytes(privateKey)
        // X25519 clamps internally; explicit clamp keeps us deterministic.
        X25519.scalarMultBase(privateKey, 0, publicKey, 0)
    }

    val isReady: Boolean
        get() = symmetricKey != null && nonceSalt != null

    /**
     * Run X25519 against the peer's 32-byte public key, then HKDF-SHA256
     * to install the AES-256-GCM key and 8-byte nonce salt. Throws
     * [IllegalArgumentException] if [peerPublicKey] is not 32 bytes long.
     */
    fun deriveSession(peerPublicKey: ByteArray) {
        require(peerPublicKey.size == PUBLIC_KEY_SIZE) {
            "peer public key must be $PUBLIC_KEY_SIZE bytes, got ${peerPublicKey.size}"
        }
        val shared = ByteArray(32)
        X25519.scalarMult(privateKey, 0, peerPublicKey, 0, shared, 0)

        val keyBytes = hkdfExpand(shared, HKDF_SALT, HKDF_INFO_KEY, 32)
        val saltBytes = hkdfExpand(shared, HKDF_SALT, HKDF_INFO_SALT, NONCE_SALT_SIZE)
        symmetricKey = SecretKeySpec(keyBytes, "AES")
        nonceSalt = saltBytes
        // Best-effort wipe of intermediate material.
        shared.fill(0)
    }

    /** Reset session key material; the public key remains valid. */
    fun reset() {
        symmetricKey = null
        nonceSalt = null
    }

    /**
     * Drop-in replacement for the legacy decryptor entry point. Returns
     * true when the 28-byte frame decrypts and verifies; the callback is
     * invoked exactly once on success.
     */
    fun decryptPayloadTo(bleData: ByteArray, onDecrypted: (Float, Float) -> Unit): Boolean {
        if (bleData.size != FRAME_SIZE) return false
        val key = symmetricKey ?: return false
        val salt = nonceSalt ?: return false

        val counterBytes = bleData.copyOfRange(0, COUNTER_PREFIX_SIZE)
        val cipherEnd = COUNTER_PREFIX_SIZE + PLAINTEXT_SIZE + TAG_SIZE
        // ciphertext + tag are passed together to AES/GCM/NoPadding.
        val ciphertextWithTag = bleData.copyOfRange(COUNTER_PREFIX_SIZE, cipherEnd)
        val nonce = ByteArray(NONCE_SALT_SIZE + COUNTER_PREFIX_SIZE)
        System.arraycopy(salt, 0, nonce, 0, NONCE_SALT_SIZE)
        System.arraycopy(counterBytes, 0, nonce, NONCE_SALT_SIZE, COUNTER_PREFIX_SIZE)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plain = cipher.doFinal(ciphertextWithTag)
            if (plain.size != PLAINTEXT_SIZE) return false
            val xBits = readIntLe(plain, 0)
            val yBits = readIntLe(plain, 4)
            onDecrypted(Float.fromBits(xBits), Float.fromBits(yBits))
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /**
     * Encode (dx, dy) into a 28-byte frame using the current session.
     * Returns null if the session is not ready or AES-GCM fails.
     */
    fun buildPacket(counter: Int, dx: Float, dy: Float): ByteArray? {
        val key = symmetricKey ?: return null
        val salt = nonceSalt ?: return null
        val counterBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(counter)
            .array()
        val nonce = ByteArray(NONCE_SALT_SIZE + COUNTER_PREFIX_SIZE)
        System.arraycopy(salt, 0, nonce, 0, NONCE_SALT_SIZE)
        System.arraycopy(counterBytes, 0, nonce, NONCE_SALT_SIZE, COUNTER_PREFIX_SIZE)
        val plain = ByteBuffer.allocate(PLAINTEXT_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(dx)
            .putFloat(dy)
            .array()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertextWithTag = cipher.doFinal(plain)
            ByteBuffer.allocate(FRAME_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(counterBytes)
                .put(ciphertextWithTag)
                .array()
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * Minimal HKDF-SHA256 (RFC 5869) Expand step composed with Extract.
     * Avoids pulling BouncyCastle's higher-level digest wiring; only Mac
     * "HmacSHA256" is needed and that is on every Android JCE.
     */
    private fun hkdfExpand(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC-SHA256(salt, IKM)
        hmac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        // Expand: T(0) = empty, T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var written = 0
        var counter = 1
        var previous = ByteArray(0)
        while (written < length) {
            hmac.reset()
            hmac.update(previous)
            hmac.update(info)
            hmac.update(counter.toByte())
            previous = hmac.doFinal()
            val toCopy = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, toCopy)
            written += toCopy
            counter += 1
        }
        return out
    }
}
