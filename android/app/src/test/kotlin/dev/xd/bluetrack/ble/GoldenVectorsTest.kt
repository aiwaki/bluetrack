package dev.xd.bluetrack.ble

import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the cross-platform golden-vector fixture at
 * `host/test-vectors/feedback_v1.json` and verifies that the Android
 * implementation derives the same bytes the generator script
 * produced.
 *
 * Catches drift between Swift / Android / Python implementations of
 * the BLE feedback protocol — re-derived public keys, AES-256-GCM
 * key, nonce salt, and per-frame encrypted bytes must all match the
 * fixture. BouncyCastle's Ed25519 follows RFC 8032 deterministically,
 * so unlike Swift's hedged CryptoKit signatures we can also verify
 * the full handshake payload byte-for-byte.
 */
class GoldenVectorsTest {

    @Test
    fun goldenVectorsMatchAndroidImplementation() {
        val json = loadFixture()

        // 1. Constants match.
        val constants = json.getJSONObject("constants")
        assertEquals(FeedbackSession.PUBLIC_KEY_SIZE, constants.getInt("public_key_size"))
        assertEquals(
            FeedbackHandshakePayload.IDENTITY_PUBLIC_KEY_SIZE,
            constants.getInt("identity_public_key_size"),
        )
        assertEquals(
            FeedbackHandshakePayload.IDENTITY_SIGNATURE_SIZE,
            constants.getInt("identity_signature_size"),
        )
        assertEquals(
            FeedbackHandshakePayload.WIRE_SIZE,
            constants.getInt("handshake_write_payload_size"),
        )
        assertEquals(FeedbackSession.NONCE_SALT_SIZE, constants.getInt("nonce_salt_size"))
        assertEquals(FeedbackSession.FRAME_SIZE, constants.getInt("frame_size"))
        assertEquals("bluetrack-feedback-v1", constants.getString("hkdf_salt_utf8"))
        assertEquals("aes-256-gcm key+nonce-salt", constants.getString("hkdf_info_base_utf8"))
        assertEquals("|pin:", constants.getString("pin_prefix_utf8"))
        assertEquals("|nonce-salt", constants.getString("nonce_salt_suffix_utf8"))

        // 2. UUIDs match the protocol contract.
        assertEquals(
            "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263",
            json.getString("service_uuid"),
        )
        assertEquals(
            "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6",
            json.getString("feedback_characteristic_uuid"),
        )
        assertEquals(
            "4846ff88-f2d4-4df2-9500-9bf8ed23f9e6",
            json.getString("handshake_characteristic_uuid"),
        )

        // 3. Re-derive host X25519 + Ed25519 pubs from their seeds.
        val host = json.getJSONObject("host")
        val hostXSeed = b64(host.getString("ephemeral_x25519_private_seed_b64"))
        val expectedHostXPub = b64(host.getString("ephemeral_x25519_public_b64"))
        val derivedHostXPub = ByteArray(32).also {
            X25519.scalarMultBase(hostXSeed, 0, it, 0)
        }
        assertArrayEquals(
            "BC X25519.scalarMultBase produced different pub from seed",
            expectedHostXPub, derivedHostXPub,
        )
        val hostIdSeed = b64(host.getString("identity_ed25519_private_seed_b64"))
        val expectedHostIdPub = b64(host.getString("identity_ed25519_public_b64"))
        val derivedHostIdPub = ByteArray(32).also {
            Ed25519.generatePublicKey(hostIdSeed, 0, it, 0)
        }
        assertArrayEquals(
            "BC Ed25519.generatePublicKey produced different pub from seed",
            expectedHostIdPub, derivedHostIdPub,
        )
        assertEquals(
            host.getString("identity_fingerprint"),
            FeedbackHandshakePayload.fingerprintOf(derivedHostIdPub),
        )

        // 4. Phone X25519 pub.
        val phone = json.getJSONObject("phone")
        val phoneXSeed = b64(phone.getString("ephemeral_x25519_private_seed_b64"))
        val expectedPhoneXPub = b64(phone.getString("ephemeral_x25519_public_b64"))
        val derivedPhoneXPub = ByteArray(32).also {
            X25519.scalarMultBase(phoneXSeed, 0, it, 0)
        }
        assertArrayEquals(expectedPhoneXPub, derivedPhoneXPub)

        // 5. Build the handshake payload locally with BC Ed25519. Bytes
        //    must equal the fixture (RFC 8032 deterministic).
        val sig = ByteArray(64)
        Ed25519.sign(hostIdSeed, 0, derivedHostXPub, 0, derivedHostXPub.size, sig, 0)
        val builtHandshake = derivedHostXPub + derivedHostIdPub + sig
        val expectedHandshake = b64(json.getString("handshake_write_payload_b64"))
        assertArrayEquals(
            "BC handshake payload diverged from cross-platform fixture",
            expectedHandshake, builtHandshake,
        )
        // Locally-built handshake must verify too.
        val parsed = FeedbackHandshakePayload.parse(builtHandshake)
        assertNotNull(parsed)
        assertTrue(parsed!!.verifySignature())

        // 6. Phone derives the AES-256-GCM session from (phoneXSeed,
        //    hostXPub, pin); the resulting key + nonce salt must match
        //    the fixture.
        val pin = json.getString("pin")
        val phoneSession = FeedbackSession.fromTestSeed(phoneXSeed)
        phoneSession.deriveSession(derivedHostXPub, pin)
        val expectedAesKey = b64(json.getString("aes_key_b64"))
        val expectedNonceSalt = b64(json.getString("nonce_salt_b64"))
        assertArrayEquals(
            "Android HKDF AES key diverged",
            expectedAesKey, phoneSession.testOnlyAesKeyBytes(),
        )
        assertArrayEquals(
            "Android HKDF nonce salt diverged",
            expectedNonceSalt, phoneSession.testOnlyNonceSaltBytes(),
        )

        // 7. Decrypt every fixture frame via the Android session.
        val frames = json.getJSONArray("frames")
        assertEquals(6, frames.length())
        for (i in 0 until frames.length()) {
            val entry = frames.getJSONObject(i)
            val counter = entry.getInt("counter")
            val expectedDx = entry.getDouble("dx").toFloat()
            val expectedDy = entry.getDouble("dy").toFloat()
            val frameBytes = b64(entry.getString("frame_b64"))
            // Reset session between frames so the replay window does
            // not reject the deliberately non-monotonic test counters.
            phoneSession.deriveSession(derivedHostXPub, pin)
            var calledWith: Pair<Float, Float>? = null
            val ok = phoneSession.decryptPayloadTo(frameBytes) { dx, dy ->
                calledWith = Pair(dx, dy)
            }
            assertTrue("decrypt counter=$counter failed", ok)
            assertNotNull(calledWith)
            assertEquals(expectedDx, calledWith!!.first, 1e-6f)
            assertEquals(expectedDy, calledWith!!.second, 1e-6f)
        }

        // 8. Re-encrypt each frame via the AES-GCM key directly.
        //    AES-GCM is deterministic given (key, nonce, plaintext);
        //    the Android-built ciphertext must match the fixture.
        val keySpec = SecretKeySpec(expectedAesKey, "AES")
        for (i in 0 until frames.length()) {
            val entry = frames.getJSONObject(i)
            val counter = entry.getInt("counter")
            val expectedDx = entry.getDouble("dx").toFloat()
            val expectedDy = entry.getDouble("dy").toFloat()
            val expectedFrame = b64(entry.getString("frame_b64"))
            val counterBytes = byteArrayOf(
                (counter and 0xFF).toByte(),
                ((counter ushr 8) and 0xFF).toByte(),
                ((counter ushr 16) and 0xFF).toByte(),
                ((counter ushr 24) and 0xFF).toByte(),
            )
            val nonce = expectedNonceSalt + counterBytes
            val plain = ByteArray(8)
            val xBits = expectedDx.toRawBits()
            val yBits = expectedDy.toRawBits()
            for (j in 0 until 4) plain[j] = ((xBits ushr (j * 8)) and 0xFF).toByte()
            for (j in 0 until 4) plain[j + 4] = ((yBits ushr (j * 8)) and 0xFF).toByte()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            val ciphertextWithTag = cipher.doFinal(plain)
            val encrypted = counterBytes + ciphertextWithTag
            assertArrayEquals(
                "Android AES-GCM encrypt diverged for counter=$counter",
                expectedFrame, encrypted,
            )
        }
    }

    private fun loadFixture(): JSONObject {
        val candidates = listOf(
            // gradle test cwd is android/ → fixture is one level up.
            File("../host/test-vectors/feedback_v1.json"),
            // some IDE runners cwd at module root.
            File("../../host/test-vectors/feedback_v1.json"),
            // and some at repo root.
            File("host/test-vectors/feedback_v1.json"),
        ).map { it.absoluteFile }
        val resolved = candidates.firstOrNull { it.exists() }
            ?: error(
                "fixture not found. Tried: " +
                    candidates.joinToString(", ") { it.path },
            )
        return JSONObject(resolved.readText())
    }

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
