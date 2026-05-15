package dev.xd.bluetrack.ble

import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PayloadDecryptorTest {
    private val testPin = "246810"
    private val otherPin = "135790"

    /** Generate an Ed25519 host identity keypair (private seed, public). */
    private fun newHostIdentity(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = ByteArray(32)
        Ed25519.generatePublicKey(priv, 0, pub, 0)
        return priv to pub
    }

    /** Build a 128-byte handshake payload that the host would write. */
    private fun buildHandshake(
        eph: ByteArray,
        idPriv: ByteArray,
        idPub: ByteArray,
    ): ByteArray {
        val sig = ByteArray(64)
        Ed25519.sign(idPriv, 0, eph, 0, eph.size, sig, 0)
        return eph + idPub + sig
    }

    /** Convenience: a fresh decryptor with an empty in-memory trust store. */
    private fun newDecryptor(): Pair<PayloadDecryptor, InMemoryTrustedHostPolicy> {
        val store = InMemoryTrustedHostPolicy()
        return PayloadDecryptor(store) to store
    }

    @Test
    fun freshSessionExposes32BytePublicKey() {
        val session = FeedbackSession()
        assertEquals(FeedbackSession.PUBLIC_KEY_SIZE, session.publicKey.size)
        assertFalse(session.isReady)
    }

    @Test
    fun pairedSessionsRoundTripFeedbackFrames() {
        val (host, phone) = pairedSessions(testPin)
        // Counters must be monotonic in unsigned semantics for the
        // receiver-side sliding replay window to accept all four.
        // -1 == 0xFFFFFFFF, the largest unsigned 32-bit value.
        val cases =
            listOf(
                Triple(0, 1.25f, -0.75f),
                Triple(7, -12.5f, 99.125f),
                Triple(42, 127.0f, -127.0f),
                Triple(-1, 0f, 0f),
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
            val ok =
                phone.decryptPayloadTo(packet) { x, y ->
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
        val ok =
            phoneB.decryptPayloadTo(packet) { _, _ ->
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
        assertEquals(
            "246810".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("246810")?.toList(),
        )
        assertEquals(
            "123456".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("  123456  ")?.toList(),
        )
        assertEquals(
            "1234".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("1234")?.toList(),
        )
        assertEquals(
            "123456789012".toByteArray(Charsets.US_ASCII).toList(),
            FeedbackSession.normalizedPinBytes("123456789012")?.toList(),
        )
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
        val ok =
            phone.decryptPayloadTo(packet) { _, _ ->
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
        val (phone, store) = newDecryptor()
        phone.rotateSession(testPin)
        val (idPriv, idPub) = newHostIdentity()
        val hostSession = FeedbackSession()

        val handshake = buildHandshake(hostSession.publicKey, idPriv, idPub)
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(handshake),
        )
        hostSession.deriveSession(phone.publicKey, testPin)
        assertTrue(phone.isSessionReady)
        // Identity got TOFU-pinned.
        assertTrue(idPub.contentEquals(store.trustedIdentityPublicKey()))

        val packet = hostSession.buildPacket(123, 4.0f, -4.0f)!!
        assertEquals(FeedbackSession.FRAME_SIZE, packet.size)
        var decoded: Pair<Float, Float>? = null
        val ok = phone.decryptPayloadTo(packet) { x, y -> decoded = Pair(x, y) }
        assertTrue(ok)
        assertEquals(Pair(4.0f, -4.0f), decoded)

        // Wrong-length handshake rejected.
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.MALFORMED,
            phone.installHandshake(ByteArray(31)),
        )
    }

    @Test
    fun payloadDecryptorWrapperRejectsWrongPin() {
        val (phone, _) = newDecryptor()
        phone.rotateSession(testPin)
        val (idPriv, idPub) = newHostIdentity()
        val hostSession = FeedbackSession()

        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostSession.publicKey, idPriv, idPub)),
        )
        // Host derives with a DIFFERENT pin than the phone, so AES-GCM
        // tag verification must fail on every frame the host sends.
        hostSession.deriveSession(phone.publicKey, otherPin)
        val packet = hostSession.buildPacket(0, 1.0f, 1.0f)!!
        assertFalse(phone.decryptPayloadTo(packet) { _, _ -> })
    }

    @Test
    fun payloadDecryptorRotateSessionRejectsInvalidPin() {
        val (phone, _) = newDecryptor()
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
        val (phone, _) = newDecryptor()
        phone.rotateSession(testPin)
        val (idPriv, idPub) = newHostIdentity()
        val host = FeedbackSession()
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(host.publicKey, idPriv, idPub)),
        )
        host.deriveSession(phone.publicKey, testPin)
        val packet = host.buildPacket(0, 1f, 1f)!!
        assertTrue(phone.decryptPayloadTo(packet) { _, _ -> })

        // Rotating with a fresh keypair AND a fresh pin must invalidate
        // both the prior packet and the prior pin.
        phone.rotateSession(otherPin)
        assertNotEquals(host.publicKey.toList(), phone.publicKey.toList())
        assertFalse(phone.isSessionReady)
        assertFalse(phone.decryptPayloadTo(packet) { _, _ -> })
    }

    @Test
    fun installHandshakeRejectsBadEd25519Signature() {
        val (phone, store) = newDecryptor()
        phone.rotateSession(testPin)
        val (_, idPub) = newHostIdentity()
        val (otherPriv, _) = newHostIdentity()
        val hostSession = FeedbackSession()
        // Sign with the WRONG private key but advertise the real pubkey.
        val handshake = buildHandshake(hostSession.publicKey, otherPriv, idPub)
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.BAD_SIGNATURE,
            phone.installHandshake(handshake),
        )
        // Identity must NOT be pinned on a bad signature.
        assertNull(store.trustedIdentityPublicKey())
    }

    @Test
    fun installHandshakeRejectsUntrustedHostAfterPinning() {
        val (phone, store) = newDecryptor()
        phone.rotateSession(testPin)
        val (idPrivA, idPubA) = newHostIdentity()
        val (idPrivB, idPubB) = newHostIdentity()
        val hostA = FeedbackSession()
        val hostB = FeedbackSession()

        // First handshake TOFU-pins host A.
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostA.publicKey, idPrivA, idPubA)),
        )
        assertTrue(idPubA.contentEquals(store.trustedIdentityPublicKey()))

        // Rotating the session must NOT clear the trusted host pin.
        phone.rotateSession(testPin)

        // Host B presents a valid signature but a different identity.
        // Must be rejected even though the pin is correct.
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.UNTRUSTED_HOST,
            phone.installHandshake(buildHandshake(hostB.publicKey, idPrivB, idPubB)),
        )
        // Pin still on host A.
        assertTrue(idPubA.contentEquals(store.trustedIdentityPublicKey()))

        // Forget host → host B can re-pin.
        store.forget()
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostB.publicKey, idPrivB, idPubB)),
        )
        assertTrue(idPubB.contentEquals(store.trustedIdentityPublicKey()))
    }

    @Test
    fun installHandshakeAcceptsRepeatFromPinnedHost() {
        val (phone, store) = newDecryptor()
        phone.rotateSession(testPin)
        val (idPriv, idPub) = newHostIdentity()

        // First connect: pin.
        val hostA = FeedbackSession()
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostA.publicKey, idPriv, idPub)),
        )

        // Rotation simulates the next reconnect with a fresh phone keypair.
        phone.rotateSession(testPin)
        val hostB = FeedbackSession() // fresh ephemeral on the host side too
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostB.publicKey, idPriv, idPub)),
        )
        assertTrue(idPub.contentEquals(store.trustedIdentityPublicKey()))
    }

    @Test
    fun replayWindowRejectsExactReplay() {
        val (host, phone) = pairedSessions(testPin)
        val packet = host.buildPacket(5, 1.0f, 1.0f)!!
        // First delivery authenticates and updates the receiver window.
        var firstFired = false
        assertTrue(phone.decryptPayloadTo(packet) { _, _ -> firstFired = true })
        assertTrue(firstFired)
        // Replay of the exact same bytes must be dropped even though the
        // AES-GCM tag is still valid.
        assertFalse(
            phone.decryptPayloadTo(packet) { _, _ ->
                assertTrue("replay must not fire callback", false)
            },
        )
    }

    @Test
    fun replayWindowAcceptsOutOfOrderWithinWindow() {
        val (host, phone) = pairedSessions(testPin)
        // Send counters in deliberately out-of-order sequence within the
        // 64-frame window; all should authenticate exactly once.
        val sequence = listOf(10, 8, 9, 11, 0, 63)
        for (counter in sequence) {
            val packet = host.buildPacket(counter, counter.toFloat(), -counter.toFloat())!!
            assertTrue(
                "counter=$counter must authenticate",
                phone.decryptPayloadTo(packet) { _, _ -> },
            )
        }
        // Replaying any one of them now must be rejected.
        for (counter in sequence) {
            val packet = host.buildPacket(counter, counter.toFloat(), -counter.toFloat())!!
            assertFalse(
                "replay of counter=$counter must be rejected",
                phone.decryptPayloadTo(packet) { _, _ -> },
            )
        }
    }

    @Test
    fun replayWindowRejectsTooOldCounter() {
        val (host, phone) = pairedSessions(testPin)
        // Advance the window past 100; counters more than REPLAY_WINDOW_SIZE
        // back from 100 must be dropped even though the tag verifies.
        val far = host.buildPacket(100, 0f, 0f)!!
        assertTrue(phone.decryptPayloadTo(far) { _, _ -> })
        // 100 - 64 = 36; counter 35 is outside the window.
        val tooOld = host.buildPacket(35, 0f, 0f)!!
        assertFalse(phone.decryptPayloadTo(tooOld) { _, _ -> })
        // 100 - 63 = 37; counter 37 is at the window edge and must be accepted.
        val edge = host.buildPacket(37, 0f, 0f)!!
        assertTrue(phone.decryptPayloadTo(edge) { _, _ -> })
    }

    @Test
    fun replayWindowResetsOnRotateSession() {
        val (phone, _) = newDecryptor()
        phone.rotateSession(testPin)
        val (idPriv, idPub) = newHostIdentity()
        val hostA = FeedbackSession()
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostA.publicKey, idPriv, idPub)),
        )
        hostA.deriveSession(phone.publicKey, testPin)

        val pkt = hostA.buildPacket(7, 0f, 0f)!!
        assertTrue(phone.decryptPayloadTo(pkt) { _, _ -> })
        assertFalse(phone.decryptPayloadTo(pkt) { _, _ -> })

        // After rotation the receiver gets a fresh keypair and a fresh
        // window; a new host can re-use any counter it likes.
        phone.rotateSession(testPin)
        val hostB = FeedbackSession()
        assertEquals(
            PayloadDecryptor.HandshakeOutcome.OK,
            phone.installHandshake(buildHandshake(hostB.publicKey, idPriv, idPub)),
        )
        hostB.deriveSession(phone.publicKey, testPin)
        val newPkt = hostB.buildPacket(7, 0f, 0f)!!
        assertTrue(phone.decryptPayloadTo(newPkt) { _, _ -> })
    }

    @Test
    fun counterWrapAroundIsRejected() {
        val (host, phone) = pairedSessions(testPin)
        // Push the receiver to the maximum unsigned 32-bit counter.
        val max = host.buildPacket(-1, 0f, 0f)!! // -1 == 0xFFFFFFFF
        assertTrue(phone.decryptPayloadTo(max) { _, _ -> })
        // Counter wrap from 0xFFFFFFFF to 0 must NOT authenticate; the
        // host is required to rotate sessions before wrap.
        val wrapped = host.buildPacket(0, 0f, 0f)!!
        assertFalse(phone.decryptPayloadTo(wrapped) { _, _ -> })
    }

    @Test
    fun acceptCounterDirectMonotonic() {
        // Drive the sliding window directly to confirm the bitmap math.
        val (_, phone) = pairedSessions(testPin)
        // After pairedSessions the phone's window is fresh.
        // Re-derive to reset window cleanly for direct testing.
        phone.deriveSession(FeedbackSession().publicKey, testPin)
        assertTrue(phone.acceptCounter(0))
        assertTrue(phone.acceptCounter(1))
        assertFalse(phone.acceptCounter(0)) // replay
        assertFalse(phone.acceptCounter(1)) // replay
        assertTrue(phone.acceptCounter(5))
        assertTrue(phone.acceptCounter(3)) // out-of-order, in window
        assertFalse(phone.acceptCounter(3)) // replay of in-window
        assertTrue(phone.acceptCounter(70)) // jumps past window edge
        assertFalse(phone.acceptCounter(5)) // now too-old
        assertTrue(phone.acceptCounter(70 - 63)) // window edge accepts
        assertFalse(phone.acceptCounter(70 - 64)) // one beyond edge rejects
    }

    private fun pairedSessions(pin: String): Pair<FeedbackSession, FeedbackSession> {
        val host = FeedbackSession()
        val phone = FeedbackSession()
        host.deriveSession(phone.publicKey, pin)
        phone.deriveSession(host.publicKey, pin)
        return host to phone
    }
}
