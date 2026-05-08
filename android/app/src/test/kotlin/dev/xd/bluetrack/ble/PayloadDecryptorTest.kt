package dev.xd.bluetrack.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadDecryptorTest {

    private val testPin = "246810"
    private val otherPin = "135790"

    @Test
    fun freshSessionExposes32BytePublicKey() {
        val session = FeedbackSession()
        assertEquals(FeedbackSession.PUBLIC_KEY_SIZE, session.publicKey.size)
        assertFalse(session.isReady)
    }

    @Test
    fun pairedSessionsRoundTripFeedbackFrames() {
        val (host, phone) = pairedSessions(testPin)
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
        val (hostA, _) = pairedSessions(testPin)
        val (_, phoneB) = pairedSessions(testPin)
        val packet = hostA.buildPacket(0, 1.0f, 1.0f)!!
        val ok = phoneB.decryptPayloadTo(packet) { _, _ ->
            assertTrue("callback must not fire", false)
        }
        assertFalse(ok)
    }

    @Test
    fun decryptRejectsTamperedTag() {
        val (host, phone) = pairedSessions(testPin)
        val packet = host.buildPacket(5, 0.5f, 0.5f)!!
        packet[packet.size - 1] = (packet[packet.size - 1].toInt() xor 0x01).toByte()
        val ok = phone.decryptPayloadTo(packet) { _, _ -> }
        assertFalse(ok)
    }

    @Test
    fun decryptRejectsWrongCounterPrefix() {
        val (host, phone) = pairedSessions(testPin)
        val packet = host.buildPacket(5, 0.5f, 0.5f)!!
        packet[0] = (packet[0].toInt() xor 0x01).toByte()
        val ok = phone.decryptPayloadTo(packet) { _, _ -> }
        assertFalse(ok)
    }

    @Test
    fun decryptRejectsWrongLength() {
        val (_, phone) = pairedSessions(testPin)
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
            session.deriveSession(ByteArray(16), testPin)
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
        assertFalse(session.isReady)
    }

    @Test
    fun deriveSessionRequiresValidPin() {
        val host = FeedbackSession()
        val peer = FeedbackSession().publicKey
        for (badPin in listOf("", "12", "123", "1234567890123", "12ab56", "12 34", "12-456")) {
            try {
                host.deriveSession(peer, badPin)
                assertTrue("should have thrown for pin '$badPin'", false)
            } catch (_: IllegalArgumentException) {
                // ok
            }
        }
        assertFalse(host.isReady)
    }

    @Test
    fun normalizedPinBytesValidation() {
        assertEquals("246810".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("246810")?.toList())
        assertEquals("123456".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("  123456  ")?.toList())
        assertEquals("1234".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("1234")?.toList())
        assertEquals("123456789012".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("123456789012")?.toList())
        assertNull(FeedbackSession.normalizedPinBytes("123"))
        assertNull(FeedbackSession.normalizedPinBytes("1234567890123"))
        assertNull(FeedbackSession.normalizedPinBytes("12 34"))
        assertNull(FeedbackSession.normalizedPinBytes("12-456"))
        assertNull(FeedbackSession.normalizedPinBytes(""))
    }

    @Test
    fun freshKeysAreNotEqual() {
        val a = FeedbackSession()
        val b = FeedbackSession()
        assertNotEquals(a.publicKey.toList(), b.publicKey.toList())
    }

    @Test
    fun ecdhProducesIdenticalDerivedKeyOnBothSides() {
        val (host, phone) = pairedSessions(testPin)
        val packetA = host.buildPacket(1, 2.5f, -2.5f)!!
        val packetB = phone.buildPacket(1, 2.5f, -2.5f)!!
        var ok = false
        host.decryptPayloadTo(packetB) { _, _ -> ok = true }
        assertTrue(ok)
        ok = false
        phone.decryptPayloadTo(packetA) { _, _ -> ok = true }
        assertTrue(ok)
    }

    @Test
    fun phoneRejectsHostFramesWithWrongPin() {
        // Same X25519 exchange, host derives with wrong pin → AES-GCM tag
        // verification on the phone must fail. This is the headline
        // protection the pin adds: a snoop that sees the public keys but
        // not the pin cannot inject feedback.
        val host = FeedbackSession()
        val phone = FeedbackSession()
        host.deriveSession(phone.publicKey, otherPin)
        phone.deriveSession(host.publicKey, testPin)

        val packet = host.buildPacket(0, 1.0f, 1.0f)!!
        val ok = phone.decryptPayloadTo(packet) { _, _ ->
            assertTrue("callback must not fire", false)
        }
        assertFalse(ok)
    }

    @Test
    fun hostWithWrongPinCannotDecryptPhoneFrames() {
        val host = FeedbackSession()
        val phone = FeedbackSession()
        host.deriveSession(phone.publicKey, otherPin)
        phone.deriveSession(host.publicKey, testPin)

        val packet = phone.buildPacket(7, 9.0f, -9.0f)!!
        assertFalse(host.decryptPayloadTo(packet) { _, _ -> })
    }

    @Test
    fun payloadDecryptorWrapperHandshakeAndDecryptRoundTrip() {
        val phone = PayloadDecryptor()
        phone.rotateSession(testPin)
        val hostSession = FeedbackSession()

        assertTrue(phone.installPeerPublicKey(hostSession.publicKey))
        hostSession.deriveSession(phone.publicKey, testPin)
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
    fun payloadDecryptorWrapperRejectsWrongPin() {
        val phone = PayloadDecryptor()
        phone.rotateSession(testPin)
        val hostSession = FeedbackSession()

        assertTrue(phone.installPeerPublicKey(hostSession.publicKey))
        hostSession.deriveSession(phone.publicKey, otherPin)

        val packet = hostSession.buildPacket(0, 1.0f, 1.0f)!!
        assertFalse(phone.decryptPayloadTo(packet) { _, _ -> })
    }

    @Test
    fun payloadDecryptorRotateSessionRejectsInvalidPin() {
        val phone = PayloadDecryptor()
        try {
            phone.rotateSession("12")
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
        try {
            phone.rotateSession("12abcd")
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun rotateSessionInvalidatesPriorHandshake() {
        val phone = PayloadDecryptor()
        phone.rotateSession(testPin)
        val host = FeedbackSession()
        assertTrue(phone.installPeerPublicKey(host.publicKey))
        host.deriveSession(phone.publicKey, testPin)
        val packet = host.buildPacket(0, 1f, 1f)!!
        assertTrue(phone.decryptPayloadTo(packet) { _, _ -> })

        // Rotating with a fresh keypair AND a fresh pin must invalidate
        // both the prior packet and the prior pin. Same host re-deriving
        // against the new public key with the OLD pin should also fail.
        phone.rotateSession(otherPin)
        assertNotEquals(host.publicKey.toList(), phone.publicKey.toList())
        assertFalse(phone.isSessionReady)
        assertFalse(phone.decryptPayloadTo(packet) { _, _ -> })
    }

    private fun pairedSessions(pin: String): Pair<FeedbackSession, FeedbackSession> {
        val host = FeedbackSession()
        val phone = FeedbackSession()
        host.deriveSession(phone.publicKey, pin)
        phone.deriveSession(host.publicKey, pin)
        return host to phone
    }
}
