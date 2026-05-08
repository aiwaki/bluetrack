package dev.xd.bluetrack.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadDecryptorTest {

    @Test
    fun freshSessionExposes32BytePublicKey() {
        val session = FeedbackSession()
        assertEquals(FeedbackSession.PUBLIC_KEY_SIZE, session.publicKey.size)
        assertFalse(session.isReady)
    }

    @Test
    fun pairedSessionsRoundTripFeedbackFrames() {
        val (host, phone) = pairedSessions()
        val cases = listOf(
            Triple(0, 1.25f, -0.75f),
            Triple(7, -12.5f, 99.125f),
            Triple(-1, 0f, 0f),
            Triple(42, 127.0f, -127.0f),
        )
        for ((counter, dx, dy) in cases) {
            val packet = host.buildPacket(counter, dx, dy)
            assertNotNull("buildPacket counter=$counter", packet)
            assertEquals(FeedbackSession.FRAME_SIZE, packet!!.size)
            // Counter prefix is little-endian.
            assertEquals(counter.toByte(), packet[0])
            assertEquals(((counter ushr 8) and 0xFF).toByte(), packet[1])
            assertEquals(((counter ushr 16) and 0xFF).toByte(), packet[2])
            assertEquals(((counter ushr 24) and 0xFF).toByte(), packet[3])

            var decoded = false
            val ok = phone.decryptPayloadTo(packet) { x, y ->
                assertEquals(dx, x, 1e-6f)
                assertEquals(dy, y, 1e-6f)
                decoded = true
            }
            assertTrue("decryptPayloadTo counter=$counter", ok)
            assertTrue("callback fired counter=$counter", decoded)
        }
    }

    @Test
    fun differentSessionsCannotDecryptEachOther() {
        val (hostA, _) = pairedSessions()
        val (_, phoneB) = pairedSessions()
        val packet = hostA.buildPacket(0, 1.0f, 1.0f)!!
        val ok = phoneB.decryptPayloadTo(packet) { _, _ ->
            assertTrue("callback must not fire", false)
        }
        assertFalse(ok)
    }

    @Test
    fun decryptRejectsTamperedTag() {
        val (host, phone) = pairedSessions()
        val packet = host.buildPacket(5, 0.5f, 0.5f)!!
        // Flip bit in the auth tag (last byte).
        packet[packet.size - 1] = (packet[packet.size - 1].toInt() xor 0x01).toByte()
        val ok = phone.decryptPayloadTo(packet) { _, _ -> }
        assertFalse(ok)
    }

    @Test
    fun decryptRejectsWrongCounterPrefix() {
        val (host, phone) = pairedSessions()
        val packet = host.buildPacket(5, 0.5f, 0.5f)!!
        // Change the counter prefix without re-encrypting; nonce mismatch
        // means the GCM tag no longer authenticates.
        packet[0] = (packet[0].toInt() xor 0x01).toByte()
        val ok = phone.decryptPayloadTo(packet) { _, _ -> }
        assertFalse(ok)
    }

    @Test
    fun decryptRejectsWrongLength() {
        val (_, phone) = pairedSessions()
        assertFalse(phone.decryptPayloadTo(byteArrayOf(0, 1, 2)) { _, _ -> })
        assertFalse(phone.decryptPayloadTo(ByteArray(FeedbackSession.FRAME_SIZE - 1)) { _, _ -> })
        assertFalse(phone.decryptPayloadTo(ByteArray(FeedbackSession.FRAME_SIZE + 1)) { _, _ -> })
    }

    @Test
    fun decryptRejectsBeforeSessionInstalled() {
        val phone = FeedbackSession()
        assertFalse(phone.decryptPayloadTo(ByteArray(FeedbackSession.FRAME_SIZE)) { _, _ -> })
    }

    @Test
    fun deriveSessionRequires32ByteKey() {
        val session = FeedbackSession()
        try {
            session.deriveSession(ByteArray(16))
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
        assertFalse(session.isReady)
    }

    @Test
    fun freshKeysAreNotEqual() {
        val a = FeedbackSession()
        val b = FeedbackSession()
        assertNotEquals(a.publicKey.toList(), b.publicKey.toList())
    }

    @Test
    fun ecdhProducesIdenticalDerivedKeyOnBothSides() {
        // Verify the agreement: both sides must accept frames the other built.
        val (host, phone) = pairedSessions()
        val packetA = host.buildPacket(1, 2.5f, -2.5f)!!
        val packetB = phone.buildPacket(1, 2.5f, -2.5f)!!
        // Host built one and phone built one with the same payload+counter;
        // both should decrypt cross-wise (host can decrypt phone, vice versa).
        var ok = false
        host.decryptPayloadTo(packetB) { _, _ -> ok = true }
        assertTrue(ok)
        ok = false
        phone.decryptPayloadTo(packetA) { _, _ -> ok = true }
        assertTrue(ok)
    }

    @Test
    fun payloadDecryptorWrapperHandshakeAndDecryptRoundTrip() {
        val phone = PayloadDecryptor()
        val hostSession = FeedbackSession()

        // Phone -> host: phone exposes its pubkey via PayloadDecryptor.publicKey.
        // Host -> phone: writes its pubkey, decryptor installs it.
        assertTrue(phone.installPeerPublicKey(hostSession.publicKey))
        hostSession.deriveSession(phone.publicKey)
        assertTrue(phone.isSessionReady)

        val packet = hostSession.buildPacket(123, 4.0f, -4.0f)!!
        assertEquals(FeedbackSession.FRAME_SIZE, packet.size)
        var decoded: Pair<Float, Float>? = null
        val ok = phone.decryptPayloadTo(packet) { x, y -> decoded = Pair(x, y) }
        assertTrue(ok)
        assertEquals(Pair(4.0f, -4.0f), decoded)

        // Wrong-length pubkey rejected.
        assertFalse(phone.installPeerPublicKey(ByteArray(31)))
    }

    @Test
    fun rotateSessionInvalidatesPriorHandshake() {
        val phone = PayloadDecryptor()
        val host = FeedbackSession()
        assertTrue(phone.installPeerPublicKey(host.publicKey))
        host.deriveSession(phone.publicKey)
        val packet = host.buildPacket(0, 1f, 1f)!!
        assertTrue(phone.decryptPayloadTo(packet) { _, _ -> })

        // After rotateSession, the prior packet must no longer authenticate
        // because the keypair (and thus the AES-GCM key) has changed.
        phone.rotateSession()
        assertNotEquals(host.publicKey.toList(), phone.publicKey.toList())
        assertFalse(phone.isSessionReady)
        assertFalse(phone.decryptPayloadTo(packet) { _, _ -> })
    }

    private fun pairedSessions(): Pair<FeedbackSession, FeedbackSession> {
        val host = FeedbackSession()
        val phone = FeedbackSession()
        host.deriveSession(phone.publicKey)
        phone.deriveSession(host.publicKey)
        return host to phone
    }
}
