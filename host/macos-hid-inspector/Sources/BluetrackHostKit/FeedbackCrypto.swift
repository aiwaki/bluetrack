import CryptoKit
import Foundation

/// BLE feedback service contract: per-session ECDH key agreement + AEAD.
///
/// **Handshake.** Each side generates an ephemeral X25519 key pair on session
/// start. The host writes its 32-byte public key to the handshake
/// characteristic, then reads the same characteristic to get the peer's
/// 32-byte public key. Both sides compute the X25519 shared secret and run
/// HKDF-SHA256 over it to derive a 32-byte AES-256-GCM key plus an 8-byte
/// nonce salt:
///
/// ```
/// out         = HKDF-SHA256(IKM=ECDH(secret), salt=hkdfSaltBytes,
///                            info=hkdfInfoBytes, L=40)
/// AES_KEY     = out[0..<32]
/// NONCE_SALT  = out[32..<40]
/// ```
///
/// **Frame layout** (28 bytes on the feedback characteristic):
///
/// ```
/// [0..<4]    counter (uint32, little-endian)
/// [4..<12]   AES-256-GCM ciphertext of (Float32 dx, Float32 dy) little-endian
/// [12..<28]  AES-256-GCM 16-byte authentication tag
/// ```
///
/// **Nonce** is `NONCE_SALT (8 bytes) || counter_LE (4 bytes)`. The host
/// MUST use a unique counter per packet within a session and rotate the
/// session before counter wrap (2^32 packets ≈ 248 days at 5ms cadence).
public enum FeedbackCrypto {
    public static let serviceUUIDString = "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"
    public static let feedbackCharacteristicUUIDString = "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
    public static let handshakeCharacteristicUUIDString = "4846ff88-f2d4-4df2-9500-9bf8ed23f9e6"

    /// Bytes on the wire for one encrypted frame.
    public static let frameSize = 28
    /// Counter prefix length inside `frameSize`.
    public static let counterPrefixSize = 4
    /// Plaintext payload size (Float32 dx, Float32 dy little-endian).
    public static let plaintextSize = 8
    /// AES-GCM authentication tag size we always emit.
    public static let tagSize = 16
    /// Length of an X25519 public or private key on the wire.
    public static let publicKeySize = 32
    /// Length of an Ed25519 host identity public key on the wire.
    public static let identityPublicKeySize = 32
    /// Length of an Ed25519 signature on the wire.
    public static let identitySignatureSize = 64
    /// Total bytes the host writes to the handshake characteristic:
    /// ephemeral X25519 pubkey, Ed25519 identity pubkey, Ed25519 sig
    /// over the ephemeral pubkey.
    public static let handshakeWritePayloadSize =
        publicKeySize + identityPublicKeySize + identitySignatureSize
    /// Length of the HKDF-derived nonce salt prefix.
    public static let nonceSaltSize = 8

    /// HKDF salt for key + nonce-salt expansion. Domain-separates this
    /// protocol from any future Bluetrack key derivations.
    public static let hkdfSaltBytes: [UInt8] = Array("bluetrack-feedback-v1".utf8)

    /// HKDF info argument; binds the derived material to AES-256-GCM.
    /// The peripheral-displayed pairing pin is appended at derivation time
    /// (`<base>|pin:<digits>` for the key, `<base>|pin:<digits>|nonce-salt`
    /// for the 8-byte nonce salt) so a host that does not know the pin
    /// derives different key material and AES-GCM tag verification fails.
    public static let hkdfInfoBytes: [UInt8] = Array("aes-256-gcm key+nonce-salt".utf8)

    /// Acceptable pairing-pin length range. The peripheral generates a
    /// random pin in this range per `BleHidGateway.startGatt`.
    public static let pinMinLength = 4
    public static let pinMaxLength = 12

    /// Trim whitespace and validate that `pin` contains only ASCII digits
    /// of an acceptable length. Returns the canonical pin bytes ready to
    /// feed into HKDF, or nil for invalid input.
    public static func normalizedPinBytes(_ pin: String) -> [UInt8]? {
        let trimmed = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= pinMinLength, trimmed.count <= pinMaxLength else {
            return nil
        }
        for ch in trimmed.unicodeScalars {
            guard ch.isASCII, ch.value >= 0x30, ch.value <= 0x39 else {
                return nil
            }
        }
        return Array(trimmed.utf8)
    }
}

/// Errors a `FeedbackSession` can produce.
public enum FeedbackSessionError: Error, Equatable {
    /// `deriveSession` was called with the wrong byte count.
    case invalidPeerPublicKey
    /// `deriveSession` was called with a pin that does not match
    /// `FeedbackCrypto.normalizedPinBytes` (non-digit, wrong length).
    case invalidPin
    /// `buildPacket` / `decodePacket` was called before `deriveSession`.
    case sessionNotReady
}

/// Mutable per-connection feedback session. Holds the ephemeral X25519
/// private key, the peer-derived AES-GCM key, and a counter-based nonce
/// salt. Construct one per BLE connection on the host side and another per
/// GATT-server lifetime on the peripheral side.
public final class FeedbackSession {
    private let privateKey: Curve25519.KeyAgreement.PrivateKey
    private var symmetricKey: SymmetricKey?
    private var nonceSalt: [UInt8]?

    /// Convenience: serialized public key, ready to write to the handshake
    /// characteristic (or return from a read).
    public let publicKey: Data

    public init() {
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        self.privateKey = privateKey
        self.publicKey = privateKey.publicKey.rawRepresentation
    }

    /// Test-only initializer that accepts a pre-generated private key
    /// (e.g. derived from a fixed seed). Used by `GoldenVectorsTests`
    /// to load the cross-platform fixture; production code should
    /// always go through the random `init()` above.
    internal init(privateKey: Curve25519.KeyAgreement.PrivateKey) {
        self.privateKey = privateKey
        self.publicKey = privateKey.publicKey.rawRepresentation
    }

    /// True after `deriveSession(peerPublicKey:)` has installed key material.
    public var isReady: Bool { symmetricKey != nil && nonceSalt != nil }

    /// Run X25519 against the peer's 32-byte public key, then HKDF-SHA256
    /// to install the AES-256-GCM key and 8-byte nonce salt. The pairing
    /// `pin` is mixed into the HKDF info so a host that does not know the
    /// peripheral-displayed pin derives different keys and the first AES-GCM
    /// frame fails authentication.
    public func deriveSession(peerPublicKey: Data, pin: String) throws {
        guard peerPublicKey.count == FeedbackCrypto.publicKeySize else {
            throw FeedbackSessionError.invalidPeerPublicKey
        }
        guard let pinBytes = FeedbackCrypto.normalizedPinBytes(pin) else {
            throw FeedbackSessionError.invalidPin
        }
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKey)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peer)
        let pinSuffix = Array("|pin:".utf8) + pinBytes
        let derivedKey = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(FeedbackCrypto.hkdfSaltBytes),
            sharedInfo: Data(FeedbackCrypto.hkdfInfoBytes + pinSuffix),
            outputByteCount: 32
        )
        let derivedSalt = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(FeedbackCrypto.hkdfSaltBytes),
            sharedInfo: Data(FeedbackCrypto.hkdfInfoBytes + pinSuffix + Array("|nonce-salt".utf8)),
            outputByteCount: FeedbackCrypto.nonceSaltSize
        )
        symmetricKey = derivedKey
        nonceSalt = derivedSalt.withUnsafeBytes { Array($0) }
    }

    /// Reset the session so it can be reused. After this, `isReady` is
    /// false and the public key remains valid.
    public func reset() {
        symmetricKey = nil
        nonceSalt = nil
    }

    /// Encode `(dx, dy)` into a 28-byte frame using the current session.
    /// Throws `sessionNotReady` if `deriveSession` has not run.
    public func buildPacket(counter: UInt32, dx: Float, dy: Float) throws -> Data {
        guard let key = symmetricKey, let salt = nonceSalt else {
            throw FeedbackSessionError.sessionNotReady
        }
        let counterBytes = uint32LE(counter)
        var nonceBytes = [UInt8]()
        nonceBytes.reserveCapacity(12)
        nonceBytes.append(contentsOf: salt)
        nonceBytes.append(contentsOf: counterBytes)
        let nonce = try AES.GCM.Nonce(data: Data(nonceBytes))
        let plain = floatLE(dx) + floatLE(dy)
        let sealed = try AES.GCM.seal(Data(plain), using: key, nonce: nonce)
        // sealed.ciphertext is 8 bytes; sealed.tag is 16 bytes.
        var out = Data(capacity: FeedbackCrypto.frameSize)
        out.append(contentsOf: counterBytes)
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out
    }

    /// Decode a 28-byte frame. Returns `nil` on length mismatch or tag
    /// failure (i.e., wrong counter, wrong key, or tampered bytes).
    /// Throws `sessionNotReady` if `deriveSession` has not run.
    public func decodePacket(_ packet: Data) throws -> (dx: Float, dy: Float)? {
        guard let key = symmetricKey, let salt = nonceSalt else {
            throw FeedbackSessionError.sessionNotReady
        }
        guard packet.count == FeedbackCrypto.frameSize else { return nil }
        let bytes = Array(packet)
        let counterBytes = Array(bytes[0..<FeedbackCrypto.counterPrefixSize])
        let cipher = Array(bytes[FeedbackCrypto.counterPrefixSize..<(FeedbackCrypto.counterPrefixSize + FeedbackCrypto.plaintextSize)])
        let tag = Array(bytes[(FeedbackCrypto.counterPrefixSize + FeedbackCrypto.plaintextSize)..<FeedbackCrypto.frameSize])
        var nonceBytes = [UInt8]()
        nonceBytes.reserveCapacity(12)
        nonceBytes.append(contentsOf: salt)
        nonceBytes.append(contentsOf: counterBytes)
        do {
            let nonce = try AES.GCM.Nonce(data: Data(nonceBytes))
            let sealed = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(cipher), tag: Data(tag))
            let plain = try AES.GCM.open(sealed, using: key)
            let plainBytes = Array(plain)
            guard plainBytes.count == FeedbackCrypto.plaintextSize else { return nil }
            return (floatFromLE(plainBytes, offset: 0), floatFromLE(plainBytes, offset: 4))
        } catch {
            return nil
        }
    }

    private func uint32LE(_ value: UInt32) -> [UInt8] {
        let little = value.littleEndian
        return withUnsafeBytes(of: little) { Array($0) }
    }

    private func floatLE(_ value: Float) -> [UInt8] {
        return uint32LE(value.bitPattern)
    }

    private func floatFromLE(_ bytes: [UInt8], offset: Int) -> Float {
        let bits = UInt32(bytes[offset])
            | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16)
            | (UInt32(bytes[offset + 3]) << 24)
        return Float(bitPattern: UInt32(littleEndian: bits))
    }
}
