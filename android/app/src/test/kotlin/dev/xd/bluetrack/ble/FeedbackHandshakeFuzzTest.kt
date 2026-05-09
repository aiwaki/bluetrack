package dev.xd.bluetrack.ble

import kotlin.random.Random
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzz tests for the handshake parser and the feedback decryption
 * paths that are reached from the BLE GATT server callback. The
 * goal is purely defensive: regardless of what bytes a peer writes
 * to the handshake or feedback characteristic, the peripheral must
 * never crash, never throw past the BleHidGateway boundary, and
 * always classify the outcome via the public enum.
 *
 * Deterministic — every test fixes its `Random` seed so a future
 * regression is reproducible from the failing log line.
 */
class FeedbackHandshakeFuzzTest {

    private val pin = "246810"

    @Test
    fun handshakeParseAcceptsOnlyExactly128Bytes() {
        val rng = Random(0xBEEFL)
        // Sweep every length from 0 to 256 except 128. parse() must
        // return null and never throw.
        for (length in 0..256) {
            if (length == FeedbackHandshakePayload.WIRE_SIZE) continue
            val bytes = ByteArray(length).also { rng.nextBytes(it) }
            val parsed = FeedbackHandshakePayload.parse(bytes)
            assertNull("len=$length must be rejected by parse()", parsed)
        }
    }

    @Test
    fun installHandshakeNeverThrowsOnRandomInputs() {
        // Random byte arrays of varied sizes. Always classify the
        // result via HandshakeOutcome; never crash, never throw.
        val store = InMemoryTrustedHostPolicy()
        val decryptor = PayloadDecryptor(store)
        decryptor.rotateSession(pin)

        val rng = Random(0xC0FFEEL)
        val sizes = listOf(0, 1, 31, 32, 64, 95, 127, 128, 129, 192, 200, 256, 511)
        for (iteration in 0 until 5000) {
            val len = sizes.random(rng)
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            val outcome = decryptor.installHandshake(bytes)
            // Only OK can flip TOFU pin state — and OK requires a
            // well-formed signature, which random bytes will not
            // produce. Defensive check: if random fuzz did somehow
            // OK, the test discovered a real bug.
            assertNotEquals(
                "random fuzz iteration $iteration produced OK; bytes=${bytes.toHexPreview()}",
                PayloadDecryptor.HandshakeOutcome.OK,
                outcome,
            )
            // Wrong-length inputs short-circuit to MALFORMED.
            // 128-byte inputs that fail signature verification go
            // to BAD_SIGNATURE.
            val expectedFamily = if (len == FeedbackHandshakePayload.WIRE_SIZE) {
                listOf(
                    PayloadDecryptor.HandshakeOutcome.BAD_SIGNATURE,
                    PayloadDecryptor.HandshakeOutcome.MALFORMED,
                )
            } else {
                listOf(PayloadDecryptor.HandshakeOutcome.MALFORMED)
            }
            assertTrue(
                "iteration $iteration len=$len outcome=$outcome not in $expectedFamily",
                outcome in expectedFamily,
            )
        }
        // After 5000 random fuzz inputs the trust store must still
        // be empty — nothing was OK, so nothing got TOFU-pinned.
        assertNull(store.trustedIdentityPublicKey())
    }

    @Test
    fun installHandshakeRejectsMutatedValidPayloads() {
        // Build a valid handshake, then mutate one byte at a time and
        // confirm the result is BAD_SIGNATURE or UNTRUSTED_HOST,
        // never OK.
        val store = InMemoryTrustedHostPolicy()
        val decryptor = PayloadDecryptor(store)
        decryptor.rotateSession(pin)

        val (idPriv, idPub) = newIdentity()
        val ephSession = FeedbackSession()
        val sig = ByteArray(64)
        Ed25519.sign(idPriv, 0, ephSession.publicKey, 0, ephSession.publicKey.size, sig, 0)
        val valid = ephSession.publicKey + idPub + sig

        val rng = Random(0xDEADBEEFL)
        for (iteration in 0 until 1000) {
            val flipIndex = rng.nextInt(valid.size)
            val flipMask = (1 shl rng.nextInt(8)).toByte()
            val mutated = valid.copyOf()
            mutated[flipIndex] = (mutated[flipIndex].toInt() xor flipMask.toInt()).toByte()
            val outcome = decryptor.installHandshake(mutated)
            // OK is the only outcome we strictly forbid: a single-bit
            // flip cannot survive Ed25519 verification AND identity
            // pinning AND HKDF.
            assertNotEquals(
                "iteration=$iteration flipIndex=$flipIndex outcome=$outcome unexpectedly OK",
                PayloadDecryptor.HandshakeOutcome.OK,
                outcome,
            )
        }
        // Trust store remains empty across mutated inputs.
        assertNull(store.trustedIdentityPublicKey())
    }

    @Test
    fun installHandshakeRecoversAfterMutationsForRealPayload() {
        // After 100 mutated rejections the decryptor must still
        // accept a fresh, real handshake from the same identity.
        val store = InMemoryTrustedHostPolicy()
        val decryptor = PayloadDecryptor(store)
        decryptor.rotateSession(pin)

        val (idPriv, idPub) = newIdentity()
        val rng = Random(0xCAFE_BABEL)
        repeat(100) {
            val noise = ByteArray(FeedbackHandshakePayload.WIRE_SIZE).also { rng.nextBytes(it) }
            decryptor.installHandshake(noise)
        }
        // Now feed a real, well-formed handshake.
        val ephSession = FeedbackSession()
        val sig = ByteArray(64)
        Ed25519.sign(idPriv, 0, ephSession.publicKey, 0, ephSession.publicKey.size, sig, 0)
        val real = ephSession.publicKey + idPub + sig
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            decryptor.installHandshake(real),
        )
        assertTrue(idPub.contentEquals(store.trustedIdentityPublicKey()))
    }

    @Test
    fun decryptPayloadToNeverCrashesOnRandomBytes() {
        // Drive the AES-GCM decode path with random 28-byte inputs
        // (and a couple of wrong sizes for good measure). The
        // callback must never fire and the function must return
        // false on every iteration.
        val (host, phone) = pairedSessions()
        val rng = Random(0x600D_F00DL)
        for (iteration in 0 until 5000) {
            val size = listOf(
                FeedbackSession.FRAME_SIZE,  // most common
                FeedbackSession.FRAME_SIZE,
                FeedbackSession.FRAME_SIZE,
                FeedbackSession.FRAME_SIZE - 1,
                FeedbackSession.FRAME_SIZE + 1,
                0,
                FeedbackSession.FRAME_SIZE + 64,
            ).random(rng)
            val bytes = ByteArray(size).also { rng.nextBytes(it) }
            var fired = false
            val accepted = phone.decryptPayloadTo(bytes) { _, _ -> fired = true }
            assertFalse(
                "iteration=$iteration size=$size accepted random bytes",
                accepted,
            )
            assertFalse(
                "iteration=$iteration size=$size invoked callback on random bytes",
                fired,
            )
        }
        // Sanity: a real frame from `host` still decrypts after the
        // fuzz noise (the receiver's replay window only advanced if
        // a random frame had somehow authenticated — it must not
        // have).
        val real = host.buildPacket(0, 1.0f, -1.0f)!!
        var realFired = false
        assertTrue(phone.decryptPayloadTo(real) { _, _ -> realFired = true })
        assertTrue(realFired)
    }

    @Test
    fun installHandshakeWithUntrustedHostNeverPinsAccidentally() {
        // Pin host A, then fuzz with handshakes built from random
        // identity keys. None must flip the trusted identity.
        val store = InMemoryTrustedHostPolicy()
        val decryptor = PayloadDecryptor(store)
        decryptor.rotateSession(pin)

        val (idPrivA, idPubA) = newIdentity()
        val ephA = FeedbackSession()
        val sigA = ByteArray(64)
        Ed25519.sign(idPrivA, 0, ephA.publicKey, 0, ephA.publicKey.size, sigA, 0)
        val handshakeA = ephA.publicKey + idPubA + sigA
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            decryptor.installHandshake(handshakeA),
        )
        assertTrue(idPubA.contentEquals(store.trustedIdentityPublicKey()))

        // Now fuzz: 200 random identities, well-formed handshakes
        // each. Every one must be rejected as UNTRUSTED_HOST and
        // none may rewrite the pinned identity.
        repeat(200) { iteration ->
            decryptor.rotateSession(pin)
            val (idPrivX, idPubX) = newIdentity()
            val ephX = FeedbackSession()
            val sigX = ByteArray(64)
            Ed25519.sign(idPrivX, 0, ephX.publicKey, 0, ephX.publicKey.size, sigX, 0)
            val handshakeX = ephX.publicKey + idPubX + sigX
            val outcome = decryptor.installHandshake(handshakeX)
            assertEquals(
                "iteration $iteration: untrusted handshake produced $outcome",
                PayloadDecryptor.HandshakeOutcome.UNTRUSTED_HOST,
                outcome,
            )
            assertTrue(
                "iteration $iteration: pinned identity changed",
                idPubA.contentEquals(store.trustedIdentityPublicKey()),
            )
        }
    }

    private fun newIdentity(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val pub = ByteArray(32)
        Ed25519.generatePublicKey(priv, 0, pub, 0)
        return priv to pub
    }

    private fun pairedSessions(): Pair<FeedbackSession, FeedbackSession> {
        val host = FeedbackSession()
        val phone = FeedbackSession()
        host.deriveSession(phone.publicKey, pin)
        phone.deriveSession(host.publicKey, pin)
        return host to phone
    }

    private fun ByteArray.toHexPreview(): String {
        val cap = 16
        val s = take(cap).joinToString("") { String.format("%02x", it) }
        return if (size > cap) "$s…(+${size - cap})" else s
    }
}
