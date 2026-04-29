package dev.xd.bluetrack.ble

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
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

    private val decrypted = ByteArray(8)
    private val keySpec = SecretKeySpec(keyBytes, "AES")
    private val ivSpec = IvParameterSpec(ivBytes)
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")

    @Volatile
    private var lastX: Float = 0f

    @Volatile
    private var lastY: Float = 0f

    /**
     * Hot-path API: no allocations in this function.
     */
    fun decryptPayloadTo(bleData: ByteArray, onDecrypted: (Float, Float) -> Unit): Boolean {
        if (bleData.size != 12) return false

        ivBytes[12] = bleData[0]
        ivBytes[13] = bleData[1]
        ivBytes[14] = bleData[2]
        ivBytes[15] = bleData[3]

        return try {
            synchronized(cipher) {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                cipher.update(bleData, 4, 8, decrypted, 0)
            }

            val xBits =
                (decrypted[0].toInt() and 0xFF) or
                    ((decrypted[1].toInt() and 0xFF) shl 8) or
                    ((decrypted[2].toInt() and 0xFF) shl 16) or
                    ((decrypted[3].toInt() and 0xFF) shl 24)
            val yBits =
                (decrypted[4].toInt() and 0xFF) or
                    ((decrypted[5].toInt() and 0xFF) shl 8) or
                    ((decrypted[6].toInt() and 0xFF) shl 16) or
                    ((decrypted[7].toInt() and 0xFF) shl 24)

            val x = Float.fromBits(xBits)
            val y = Float.fromBits(yBits)
            lastX = x
            lastY = y
            onDecrypted(x, y)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /**
     * Convenience API when allocations are acceptable.
     */
    fun decryptPayload(bleData: ByteArray): Pair<Float, Float>? {
        if (!decryptPayloadTo(bleData) { _, _ -> }) return null
        return Pair(lastX, lastY)
    }
}
