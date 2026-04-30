package dev.xd.bluetrack.ble

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR payload decryptor for 12-byte BLE frames:
 * [0..3]=counter (LE), [4..11]=ciphertext(dx,dy floats LE)
 */
class PayloadDecryptor {
    private val keyBytes = byteArrayOf(
        0x42, 0x6C, 0x75, 0x65, 0x74, 0x72, 0x61, 0x63,
        0x6B, 0x4B, 0x65, 0x79, 0x31, 0x32, 0x33, 0x34
    ) // "BluetrackKey1234"

    private val ivBytes = byteArrayOf(
        0x42, 0x6C, 0x75, 0x65, 0x74, 0x72, 0x61, 0x63,
        0x6B, 0x53, 0x61, 0x6C, 0x00, 0x00, 0x00, 0x00
    ) // "BluetrackSal" + dynamic LE counter

    private val keystream = ByteArray(16)
    private val decrypted = ByteArray(8)
    private val keySpec = SecretKeySpec(keyBytes, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding")

    init {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    }

    @Volatile
    private var lastX: Float = 0f

    @Volatile
    private var lastY: Float = 0f

    fun decryptPayloadTo(bleData: ByteArray, onDecrypted: (Float, Float) -> Unit): Boolean {
        if (bleData.size != 12) return false

        return try {
            val x: Float
            val y: Float
            synchronized(cipher) {
                ivBytes[12] = bleData[0]
                ivBytes[13] = bleData[1]
                ivBytes[14] = bleData[2]
                ivBytes[15] = bleData[3]

                val produced = cipher.update(ivBytes, 0, 16, keystream, 0)
                if (produced != 16) return false

                for (index in 0 until 8) {
                    decrypted[index] =
                        (bleData[index + 4].toInt() xor keystream[index].toInt()).toByte()
                }

                val xBits = readIntLe(decrypted, 0)
                val yBits = readIntLe(decrypted, 4)
                x = Float.fromBits(xBits)
                y = Float.fromBits(yBits)
            }

            lastX = x
            lastY = y
            onDecrypted(x, y)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * Convenience API when allocations are acceptable.
     */
    fun decryptPayload(bleData: ByteArray): Pair<Float, Float>? {
        if (!decryptPayloadTo(bleData) { _, _ -> }) return null
        return Pair(lastX, lastY)
    }
}
