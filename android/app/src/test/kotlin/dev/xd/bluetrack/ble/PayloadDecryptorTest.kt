package dev.xd.bluetrack.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadDecryptorTest {
    @Test
    fun decryptsCounterSpecificFrames() {
        val decryptor = PayloadDecryptor()

        assertDecrypted(decryptor, encryptedPacket(counter = 0, dx = 1.25f, dy = -0.75f), 1.25f, -0.75f)
        assertDecrypted(decryptor, encryptedPacket(counter = 7, dx = -12.5f, dy = 99.125f), -12.5f, 99.125f)
    }

    @Test
    fun rejectsInvalidFrameLength() {
        val decryptor = PayloadDecryptor()

        assertFalse(decryptor.decryptPayloadTo(byteArrayOf(0, 1, 2)) { _, _ -> })
    }

    private fun assertDecrypted(
        decryptor: PayloadDecryptor,
        packet: ByteArray,
        expectedX: Float,
        expectedY: Float,
    ) {
        var actualX = 0f
        var actualY = 0f

        val ok = decryptor.decryptPayloadTo(packet) { x, y ->
            actualX = x
            actualY = y
        }

        assertTrue(ok)
        assertEquals(expectedX, actualX, 0.0001f)
        assertEquals(expectedY, actualY, 0.0001f)
    }

    private fun encryptedPacket(counter: Int, dx: Float, dy: Float): ByteArray {
        val counterLe = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(counter)
            .array()
        val plain = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(dx)
            .putFloat(dy)
            .array()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(SALT + counterLe))
        val encrypted = cipher.update(plain) + cipher.doFinal()
        return counterLe + encrypted
    }

    private companion object {
        val KEY = "BluetrackKey1234".toByteArray()
        val SALT = "BluetrackSal".toByteArray()
    }
}
