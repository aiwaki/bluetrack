package dev.xd.bluetrack.ble

import android.content.Context
import android.util.Base64

/**
 * Persistence policy for the TOFU-pinned host Ed25519 identity. Decoupled
 * from the Android `Context` so JVM unit tests can drop in an in-memory
 * fake.
 */
interface TrustedHostPolicy {
    /** Pinned 32-byte Ed25519 public key, or null if no host is pinned. */
    fun trustedIdentityPublicKey(): ByteArray?

    /**
     * TOFU-pin `identityPublicKey` if the store is empty. Returns true
     * when the input is acceptable for the current state — either it
     * matches the previously-pinned identity, or it was just installed.
     * Returns false when the store already holds a different identity.
     */
    fun acceptOrPin(identityPublicKey: ByteArray): Boolean

    /** Clear the pinned identity. Next handshake will TOFU-pin again. */
    fun forget()

    /** Short fingerprint of the pinned host identity, or null when unset. */
    fun trustedFingerprint(): String? =
        trustedIdentityPublicKey()?.let {
            FeedbackHandshakePayload.fingerprintOf(it)
        }
}

/**
 * SharedPreferences-backed [TrustedHostPolicy].
 *
 * Storage is plain SharedPreferences. The trusted public key is not a
 * secret; only its integrity matters, and the OS already protects per-app
 * SharedPreferences from other apps under the standard Android sandbox.
 */
class TrustedHostStore(context: Context) : TrustedHostPolicy {

    private val prefs = context
        .applicationContext
        .getSharedPreferences("bluetrack_trusted_host", Context.MODE_PRIVATE)

    override fun trustedIdentityPublicKey(): ByteArray? {
        val b64 = prefs.getString(KEY_TRUSTED_PUBKEY_B64, null) ?: return null
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            if (raw.size == FeedbackHandshakePayload.IDENTITY_PUBLIC_KEY_SIZE) raw else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun acceptOrPin(identityPublicKey: ByteArray): Boolean {
        require(identityPublicKey.size == FeedbackHandshakePayload.IDENTITY_PUBLIC_KEY_SIZE) {
            "identity public key must be ${FeedbackHandshakePayload.IDENTITY_PUBLIC_KEY_SIZE} bytes"
        }
        val existing = trustedIdentityPublicKey()
        if (existing == null) {
            prefs.edit()
                .putString(
                    KEY_TRUSTED_PUBKEY_B64,
                    Base64.encodeToString(identityPublicKey, Base64.NO_WRAP),
                )
                .apply()
            return true
        }
        return existing.contentEquals(identityPublicKey)
    }

    override fun forget() {
        prefs.edit().remove(KEY_TRUSTED_PUBKEY_B64).apply()
    }

    private companion object {
        private const val KEY_TRUSTED_PUBKEY_B64 = "trusted_host_identity_pub_b64"
    }
}

/**
 * In-memory [TrustedHostPolicy] for unit tests.
 */
class InMemoryTrustedHostPolicy : TrustedHostPolicy {
    private var pinned: ByteArray? = null

    override fun trustedIdentityPublicKey(): ByteArray? = pinned?.copyOf()

    override fun acceptOrPin(identityPublicKey: ByteArray): Boolean {
        require(identityPublicKey.size == FeedbackHandshakePayload.IDENTITY_PUBLIC_KEY_SIZE)
        val existing = pinned
        if (existing == null) {
            pinned = identityPublicKey.copyOf()
            return true
        }
        return existing.contentEquals(identityPublicKey)
    }

    override fun forget() {
        pinned = null
    }
}
