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
 * MUST emit strictly monotonic counters within a session and rotate the
 * session before counter wrap; the receiver enforces a 64-frame sliding
 * replay window after AES-GCM verification, so duplicate or far-stale
 * counters are dropped even when the cryptographic tag is valid.
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
        const val PIN_MIN_LENGTH: Int = 4
        const val PIN_MAX_LENGTH: Int = 12
        /**
         * Sliding-window size for replay-protection on the receiver. Frames
         * with a counter older than this from the highest accepted counter
         * are rejected, regardless of AES-GCM tag validity. Standard 64-bit
         * IPsec ESP / SRTP-style window.
         */
        const val REPLAY_WINDOW_SIZE: Int = 64
        private const val GCM_TAG_BITS: Int = 128

        // Domain-separated HKDF salt for this protocol version.
        private val HKDF_SALT: ByteArray = "bluetrack-feedback-v1".toByteArray()
        private val HKDF_INFO_BASE: ByteArray =
            "aes-256-gcm key+nonce-salt".toByteArray()
        private val PIN_PREFIX: ByteArray = "|pin:".toByteArray()
        private val NONCE_SALT_SUFFIX: ByteArray = "|nonce-salt".toByteArray()

        /**
         * Trim whitespace and validate that [pin] contains only ASCII
         * digits within `PIN_MIN_LENGTH..PIN_MAX_LENGTH`. Returns the
         * canonical pin bytes or null on failure.
         */
        fun normalizedPinBytes(pin: String): ByteArray? {
            val trimmed = pin.trim()
            if (trimmed.length < PIN_MIN_LENGTH || trimmed.length > PIN_MAX_LENGTH) {
                return null
            }
            for (ch in trimmed) {
                if (ch < '0' || ch > '9') return null
            }
            return trimmed.toByteArray(Charsets.US_ASCII)
        }

        /**
         * Test-only seed-based constructor for the cross-platform golden
         * vector fixture. Production code always uses the random `init()`.
         */
        @JvmStatic
        internal fun fromTestSeed(seed: ByteArray): FeedbackSession {
            require(seed.size == PRIVATE_KEY_SIZE) {
                "seed must be $PRIVATE_KEY_SIZE bytes, got ${seed.size}"
            }
            val session = FeedbackSession()
            System.arraycopy(seed, 0, session.privateKey, 0, PRIVATE_KEY_SIZE)
            X25519.scalarMultBase(session.privateKey, 0, session.publicKey, 0)
            session.symmetricKey = null
            session.nonceSalt = null
            session.lastCounter = -1L
            session.replayBitmap = 0L
            return session
        }
    }

    private val privateKey = ByteArray(PRIVATE_KEY_SIZE)
    val publicKey: ByteArray = ByteArray(PUBLIC_KEY_SIZE)

    private var symmetricKey: SecretKeySpec? = null
    private var nonceSalt: ByteArray? = null

    // Replay-protection state. `lastCounter` is the highest accepted
    // counter as an unsigned 32-bit value held in a Long; -1 means "no
    // frame accepted yet". `replayBitmap` tracks which of the last
    // `REPLAY_WINDOW_SIZE` counters relative to `lastCounter` were seen,
    // bit 0 = `lastCounter` itself.
    private var lastCounter: Long = -1L
    private var replayBitmap: Long = 0L

    init {
        SecureRandom().nextBytes(privateKey)
        // X25519 clamps internally; explicit clamp keeps us deterministic.
        X25519.scalarMultBase(privateKey, 0, publicKey, 0)
    }

    /** Test-only accessor for the AES-256-GCM key bytes after derivation. */
    internal fun testOnlyAesKeyBytes(): ByteArray? = symmetricKey?.encoded

    /** Test-only accessor for the 8-byte nonce salt after derivation. */
    internal fun testOnlyNonceSaltBytes(): ByteArray? = nonceSalt?.copyOf()

    val isReady: Boolean
        get() = symmetricKey != null && nonceSalt != null

    /**
     * Run X25519 against the peer's 32-byte public key, then HKDF-SHA256
     * to install the AES-256-GCM key and 8-byte nonce salt. The pairing
     * [pin] is mixed into the HKDF info so a host that does not know the
     * peripheral-displayed pin derives different keys and the first
     * AES-GCM frame fails authentication.
     *
     * Throws [IllegalArgumentException] if [peerPublicKey] is not 32
     * bytes or [pin] does not satisfy `normalizedPinBytes` (digits only,
     * length `PIN_MIN_LENGTH..PIN_MAX_LENGTH`).
     */
    fun deriveSession(peerPublicKey: ByteArray, pin: String) {
        require(peerPublicKey.size == PUBLIC_KEY_SIZE) {
            "peer public key must be $PUBLIC_KEY_SIZE bytes, got ${peerPublicKey.size}"
        }
        val pinBytes = normalizedPinBytes(pin)
            ?: throw IllegalArgumentException(
                "pin must be $PIN_MIN_LENGTH..$PIN_MAX_LENGTH ASCII digits"
            )
        val shared = ByteArray(32)
        X25519.scalarMult(privateKey, 0, peerPublicKey, 0, shared, 0)

        val infoKey = HKDF_INFO_BASE + PIN_PREFIX + pinBytes
        val infoSalt = infoKey + NONCE_SALT_SUFFIX
        val keyBytes = hkdfExpand(shared, HKDF_SALT, infoKey, 32)
        val saltBytes = hkdfExpand(shared, HKDF_SALT, infoSalt, NONCE_SALT_SIZE)
        symmetricKey = SecretKeySpec(keyBytes, "AES")
        nonceSalt = saltBytes
        // Fresh handshake → fresh replay window.
        lastCounter = -1L
        replayBitmap = 0L
        // Best-effort wipe of intermediate material.
        shared.fill(0)
    }

    /** Reset session key material; the public key remains valid. */
    fun reset() {
        symmetricKey = null
        nonceSalt = null
        lastCounter = -1L
        replayBitmap = 0L
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
            val counter = readIntLe(bleData, 0)
            // Authenticated, but reject replays (incl. counter wrap-around)
            // before delivering to the engine.
            if (!acceptCounter(counter)) return false
            val xBits = readIntLe(plain, 0)
            val yBits = readIntLe(plain, 4)
            onDecrypted(Float.fromBits(xBits), Float.fromBits(yBits))
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /**
     * Sliding-window replay check. Returns true if [counter] is fresh
     * (newer than the highest seen, or within the replay window and not
     * previously seen) and updates internal state. Returns false on
     * exact replay, on a counter older than `lastCounter -
     * REPLAY_WINDOW_SIZE + 1`, or on counter wrap-around (the unsigned
     * counter going far backwards), which forces a session rotation.
     *
     * Visible internally so tests can drive the window directly.
     */
    @JvmName("acceptCounterForTest")
    internal fun acceptCounter(counter: Int): Boolean {
        val c = counter.toLong() and 0xFFFFFFFFL
        val last = lastCounter
        if (last < 0L) {
            lastCounter = c
            replayBitmap = 1L
            return true
        }
        return when {
            c > last -> {
                val shift = c - last
                replayBitmap = if (shift >= REPLAY_WINDOW_SIZE) 1L
                else (replayBitmap shl shift.toInt()) or 1L
                lastCounter = c
                true
            }
            c == last -> false
            else -> {
                val diff = last - c
                if (diff >= REPLAY_WINDOW_SIZE) return false
                val bit = 1L shl diff.toInt()
                if ((replayBitmap and bit) != 0L) return false
                replayBitmap = replayBitmap or bit
                true
            }
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
