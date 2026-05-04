import CommonCrypto
import Foundation

/// Encodes and decodes the 12-byte BLE feedback frame Bluetrack accepts on
/// service `0d03f2a3-…` / characteristic `4846ff87-…`.
///
/// Frame layout matches the Android decryptor and the Python sender:
///   [0..3]  counter (uint32, little-endian)
///   [4..11] AES-128-CTR ciphertext of (Float32 dx, Float32 dy) little-endian
/// IV       = "BluetrackSal" (12 bytes) + counter (4 bytes LE) → 16 bytes
/// Key      = "BluetrackKey1234"
public enum FeedbackCrypto {
    public static let serviceUUIDString = "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"
    public static let characteristicUUIDString = "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
    public static let frameSize = 12

    private static let key: [UInt8] = Array("BluetrackKey1234".utf8)
    private static let saltPrefix: [UInt8] = Array("BluetrackSal".utf8)

    public static func buildPacket(counter: UInt32, dx: Float, dy: Float) -> Data {
        precondition(key.count == 16)
        precondition(saltPrefix.count == 12)
        let counterLE = uint32LE(counter)
        let plain = floatLE(dx) + floatLE(dy)
        let keystream = aes128EcbEncrypt(block: saltPrefix + counterLE)
        var ciphertext = [UInt8](repeating: 0, count: 8)
        for index in 0..<8 {
            ciphertext[index] = plain[index] ^ keystream[index]
        }
        return Data(counterLE + ciphertext)
    }

    /// Mirror decoder used by `FeedbackCryptoTests` to round-trip a packet.
    /// The Android side does the equivalent in `PayloadDecryptor`.
    public static func decodePacket(_ packet: Data) -> (dx: Float, dy: Float)? {
        guard packet.count == frameSize else { return nil }
        let bytes = Array(packet)
        let counterLE = Array(bytes[0..<4])
        let cipher = Array(bytes[4..<12])
        let keystream = aes128EcbEncrypt(block: saltPrefix + counterLE)
        var plain = [UInt8](repeating: 0, count: 8)
        for index in 0..<8 {
            plain[index] = cipher[index] ^ keystream[index]
        }
        return (floatFromLE(plain, offset: 0), floatFromLE(plain, offset: 4))
    }

    private static func aes128EcbEncrypt(block: [UInt8]) -> [UInt8] {
        precondition(block.count == kCCBlockSizeAES128)
        var output = [UInt8](repeating: 0, count: kCCBlockSizeAES128)
        var dataMoved = 0
        let status = CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            key, key.count,
            nil,
            block, block.count,
            &output, output.count,
            &dataMoved
        )
        precondition(status == kCCSuccess, "AES ECB encrypt failed: \(status)")
        precondition(dataMoved == kCCBlockSizeAES128)
        return output
    }

    private static func uint32LE(_ value: UInt32) -> [UInt8] {
        let little = value.littleEndian
        return withUnsafeBytes(of: little) { Array($0) }
    }

    private static func floatLE(_ value: Float) -> [UInt8] {
        return uint32LE(value.bitPattern)
    }

    private static func floatFromLE(_ bytes: [UInt8], offset: Int) -> Float {
        let bits = UInt32(bytes[offset])
            | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16)
            | (UInt32(bytes[offset + 3]) << 24)
        return Float(bitPattern: UInt32(littleEndian: bits))
    }
}
