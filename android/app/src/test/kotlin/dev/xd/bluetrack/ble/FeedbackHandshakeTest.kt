package dev.xd.bluetrack.ble

import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackHandshakeTest {

    private fun newIdentity(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = ByteArray(32)
        Ed25519.generatePublicKey(priv, 0, pub, 0)
        return priv to pub
    }

    private fun build(eph: ByteArray, idPriv: ByteArray, idPub: ByteArray): ByteArray {
        val sig = ByteArray(64)
        Ed25519.sign(idPriv, 0, eph, 0, eph.size, sig, 0)
        return eph + idPub + sig
    }

    @Test
    fun wireSizeIs128() {
        assertEquals(128, FeedbackHandshakePayload.WIRE_SIZE)
        assertEquals(32, FeedbackHandshakePayload.IDENTITY_PUBLIC_KEY_SIZE)
        assertEquals(64, FeedbackHandshakePayload.IDENTITY_SIGNATURE_SIZE)
    }

    @Test
    fun parseRejectsWrongLength() {
        assertNull(FeedbackHandshakePayload.parse(ByteArray(127)))
        assertNull(FeedbackHandshakePayload.parse(ByteArray(129)))
        assertNull(FeedbackHandshakePayload.parse(ByteArray(0)))
    }

    @Test
    fun parseAcceptsExactly128Bytes() {
        val eph = ByteArray(32) { it.toByte() }
        val (priv, pub) = newIdentity()
        val parsed = FeedbackHandshakePayload.parse(build(eph, priv, pub))
        assertNotNull(parsed)
        assertEquals(32, parsed!!.ephemeralPublicKey.size)
        assertEquals(32, parsed.identityPublicKey.size)
        assertEquals(64, parsed.signature.size)
    }

    @Test
    fun verifySignatureAcceptsValidPayload() {
        val eph = FeedbackSession().publicKey
        val (priv, pub) = newIdentity()
        val parsed = FeedbackHandshakePayload.parse(build(eph, priv, pub))!!
        assertTrue(parsed.verifySignature())
    }

    @Test
    fun verifySignatureFailsOnTamperedEphemeral() {
        val eph = FeedbackSession().publicKey
        val (priv, pub) = newIdentity()
        val bytes = build(eph, priv, pub)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val parsed = FeedbackHandshakePayload.parse(bytes)!!
        assertFalse(parsed.verifySignature())
    }

    @Test
    fun verifySignatureFailsOnTamperedSignature() {
        val eph = FeedbackSession().publicKey
        val (priv, pub) = newIdentity()
        val bytes = build(eph, priv, pub)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val parsed = FeedbackHandshakePayload.parse(bytes)!!
        assertFalse(parsed.verifySignature())
    }

    @Test
    fun verifySignatureFailsWhenIdentitySwapped() {
        val eph = FeedbackSession().publicKey
        val (privA, _) = newIdentity()
        val (_, pubB) = newIdentity()
        // Sign with A but advertise B's pubkey.
        val sig = ByteArray(64)
        Ed25519.sign(privA, 0, eph, 0, eph.size, sig, 0)
        val bytes = eph + pubB + sig
        val parsed = FeedbackHandshakePayload.parse(bytes)!!
        assertFalse(parsed.verifySignature())
    }

    @Test
    fun fingerprintIs16HexCharsLowercase() {
        val (_, pub) = newIdentity()
        val fp = FeedbackHandshakePayload.fingerprintOf(pub)
        assertEquals(16, fp.length)
        for (ch in fp) {
            assertTrue(
                "fingerprint must be lowercase hex, got '$fp'",
                (ch in '0'..'9') || (ch in 'a'..'f'),
            )
        }
    }

    @Test
    fun fingerprintIsStableAcrossInvocations() {
        val (_, pub) = newIdentity()
        val a = FeedbackHandshakePayload.fingerprintOf(pub)
        val b = FeedbackHandshakePayload.fingerprintOf(pub)
        assertEquals(a, b)
    }

    @Test
    fun inMemoryTrustedHostPolicyTOFUFlow() {
        val store = InMemoryTrustedHostPolicy()
        assertNull(store.trustedIdentityPublicKey())
        assertNull(store.trustedFingerprint())

        val (_, pubA) = newIdentity()
        // First call pins.
        assertTrue(store.acceptOrPin(pubA))
        assertTrue(pubA.contentEquals(store.trustedIdentityPublicKey()))
        assertEquals(
            FeedbackHandshakePayload.fingerprintOf(pubA),
            store.trustedFingerprint(),
        )
        // Same identity again accepts.
        assertTrue(store.acceptOrPin(pubA))

        // Different identity rejects.
        val (_, pubB) = newIdentity()
        assertFalse(store.acceptOrPin(pubB))
        assertTrue(pubA.contentEquals(store.trustedIdentityPublicKey()))

        // Forget clears.
        store.forget()
        assertNull(store.trustedIdentityPublicKey())
        // Next acceptOrPin re-pins.
        assertTrue(store.acceptOrPin(pubB))
        assertTrue(pubB.contentEquals(store.trustedIdentityPublicKey()))
    }
}
