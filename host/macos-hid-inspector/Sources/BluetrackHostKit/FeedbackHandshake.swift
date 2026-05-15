import CryptoKit
import Foundation

/// 128-byte payload the host writes to the handshake characteristic.
///
/// Wire format:
/// ```
/// [0..32)    eph_x25519_public_key   (X25519 ephemeral, per session)
/// [32..64)   id_ed25519_public_key   (Ed25519 long-term host identity)
/// [64..128)  signature               (Ed25519 over eph_x25519_public_key)
/// ```
///
/// The signature commits the long-term identity to the per-session
/// ephemeral key, so a peripheral that has pinned `id_ed25519_public_key`
/// (TOFU) can refuse handshakes from any other host even if the attacker
/// learned the pairing pin. The signature does NOT commit to a peripheral
/// nonce, so a captured `(eph, id, sig)` triple can be replayed to occupy
/// the peripheral's session slot — but the attacker still needs the
/// ephemeral private key to decrypt or forge feedback frames, so the
/// replay only causes a silent timeout (no traffic). See
/// `docs/CODEX_CONTEXT.md` for the threat model.
public struct FeedbackHandshakePayload: Equatable {
    public let ephemeralPublicKey: Data
    public let identityPublicKey: Data
    public let signature: Data

    public init(
        ephemeralPublicKey: Data,
        identityPublicKey: Data,
        signature: Data
    ) {
        self.ephemeralPublicKey = ephemeralPublicKey
        self.identityPublicKey = identityPublicKey
        self.signature = signature
    }

    /// Build a 128-byte handshake payload by signing the freshly-generated
    /// ephemeral public key with the host's long-term identity key.
    public static func build(
        ephemeralPublicKey: Data,
        hostIdentity: HostIdentity
    ) throws -> FeedbackHandshakePayload {
        guard ephemeralPublicKey.count == FeedbackCrypto.publicKeySize else {
            throw FeedbackHandshakeError.invalidEphemeralKey
        }
        let signature = try hostIdentity.sign(ephemeralPublicKey)
        return FeedbackHandshakePayload(
            ephemeralPublicKey: ephemeralPublicKey,
            identityPublicKey: hostIdentity.publicKeyBytes,
            signature: signature
        )
    }

    /// 128-byte serialization matching the BLE wire format.
    public func encoded() -> Data {
        var out = Data(capacity: FeedbackCrypto.handshakeWritePayloadSize)
        out.append(ephemeralPublicKey)
        out.append(identityPublicKey)
        out.append(signature)
        return out
    }

    /// Parse an exactly-128-byte payload received over BLE. Returns nil on
    /// length mismatch.
    public static func parse(_ data: Data) -> FeedbackHandshakePayload? {
        guard data.count == FeedbackCrypto.handshakeWritePayloadSize else {
            return nil
        }
        let bytes = Array(data)
        let eph = Data(bytes[0..<FeedbackCrypto.publicKeySize])
        let idStart = FeedbackCrypto.publicKeySize
        let idEnd = idStart + FeedbackCrypto.identityPublicKeySize
        let id = Data(bytes[idStart..<idEnd])
        let sigStart = idEnd
        let sigEnd = sigStart + FeedbackCrypto.identitySignatureSize
        let sig = Data(bytes[sigStart..<sigEnd])
        return FeedbackHandshakePayload(
            ephemeralPublicKey: eph,
            identityPublicKey: id,
            signature: sig
        )
    }

    /// Verify the Ed25519 signature against the embedded identity public
    /// key. Returns true iff the signature is well-formed and valid for
    /// the embedded ephemeral pubkey.
    public func verifySignature() -> Bool {
        guard ephemeralPublicKey.count == FeedbackCrypto.publicKeySize,
              identityPublicKey.count == FeedbackCrypto.identityPublicKeySize,
              signature.count == FeedbackCrypto.identitySignatureSize else
        {
            return false
        }
        do {
            let pub = try Curve25519.Signing.PublicKey(rawRepresentation: identityPublicKey)
            return pub.isValidSignature(signature, for: ephemeralPublicKey)
        } catch {
            return false
        }
    }
}

public enum FeedbackHandshakeError: Error, Equatable {
    case invalidEphemeralKey
}
