import CryptoKit
import Foundation

/// Long-term Ed25519 identity keypair the host uses to sign BLE feedback
/// handshakes. The peripheral pins the public key on the first valid
/// handshake (Trust-On-First-Use) and refuses subsequent handshakes that
/// do not match.
///
/// Persisted as a single JSON file:
/// ```
/// { "private_key_b64": "<base64 32-byte Ed25519 seed>" }
/// ```
///
/// The default location is `~/.config/bluetrack-hid-inspector/host_identity_v1.json`.
/// The file is created with mode 0600 on first run.
public struct HostIdentity {
    public let signingKey: Curve25519.Signing.PrivateKey

    public var publicKeyBytes: Data {
        signingKey.publicKey.rawRepresentation
    }

    public init(signingKey: Curve25519.Signing.PrivateKey) {
        self.signingKey = signingKey
    }

    public init() {
        self.init(signingKey: Curve25519.Signing.PrivateKey())
    }

    /// Sign `data` with this identity's Ed25519 key. Returns 64-byte sig.
    public func sign(_ data: Data) throws -> Data {
        try signingKey.signature(for: data)
    }

    /// Stable short fingerprint of the identity public key — first 16 hex
    /// characters of SHA-256(pub). Suitable for display in CLI banners and
    /// in the Bluetrack `Trust` status row.
    public func fingerprint() -> String {
        Self.fingerprint(of: publicKeyBytes)
    }

    /// Compute the same short fingerprint over an arbitrary 32-byte
    /// identity pubkey, so the peripheral can render the trusted host
    /// fingerprint without owning the keypair.
    public static func fingerprint(of identityPublicKey: Data) -> String {
        let digest = SHA256.hash(data: identityPublicKey)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return String(hex.prefix(16))
    }

    /// Default location for the persisted identity file.
    public static var defaultURL: URL {
        let home = FileManager.default.homeDirectoryForCurrentUser
        return home
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("bluetrack-hid-inspector", isDirectory: true)
            .appendingPathComponent("host_identity_v1.json")
    }

    /// Load the identity from `url`, or generate one if the file does not
    /// exist (in which case it is written with mode 0600). Throws on
    /// corrupted or short-key files.
    public static func loadOrGenerate(at url: URL = defaultURL) throws -> HostIdentity {
        if FileManager.default.fileExists(atPath: url.path) {
            return try load(at: url)
        }
        let identity = HostIdentity()
        try identity.save(to: url)
        return identity
    }

    /// Load a previously-persisted identity from `url`.
    public static func load(at url: URL) throws -> HostIdentity {
        let data = try Data(contentsOf: url)
        let parsed = try JSONDecoder().decode(StoredIdentity.self, from: data)
        guard let raw = Data(base64Encoded: parsed.privateKeyB64),
              raw.count == 32 else {
            throw HostIdentityError.malformed
        }
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: raw)
        return HostIdentity(signingKey: key)
    }

    /// Persist this identity to `url`, creating parent directories as
    /// needed. The file is written atomically with mode 0600.
    public func save(to url: URL) throws {
        let parent = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(
            at: parent,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let stored = StoredIdentity(
            privateKeyB64: signingKey.rawRepresentation.base64EncodedString()
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(stored)
        try data.write(to: url, options: [.atomic])
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600],
            ofItemAtPath: url.path
        )
    }

    /// Delete an existing identity file. No-op if the file does not exist.
    public static func reset(at url: URL = defaultURL) throws {
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    /// Copy the identity at `source` to `destination`. Validates that
    /// the source parses cleanly first, then re-serialises through
    /// `save(to:)` so `destination` always has mode 0600 and the
    /// canonical JSON shape (sortedKeys / prettyPrinted) — even if
    /// the source on disk was hand-edited.
    ///
    /// Use `--export-identity <path>` to back up the active host
    /// identity before reformatting the host machine, switching
    /// CLIs (Swift host inspector ↔ Python sender — both file
    /// formats are byte-compatible), or rotating identities by
    /// hand without losing the previously-pinned phone trust.
    public static func export(from source: URL = defaultURL, to destination: URL) throws {
        let identity = try load(at: source)
        try identity.save(to: destination)
    }

    /// Replace the identity at `destination` (default: standard
    /// location) with the one at `source`. Validates the source
    /// JSON + 32-byte private key seed before touching `destination`,
    /// so a malformed source does not wipe an existing identity.
    /// The previous identity at `destination`, if any, is left in
    /// `destination + ".bak"` so the user can recover from a
    /// mistaken import.
    public static func importIdentity(from source: URL, to destination: URL = defaultURL) throws {
        let incoming = try load(at: source)
        if FileManager.default.fileExists(atPath: destination.path) {
            let backup = destination.appendingPathExtension("bak")
            // Best-effort: replace any previous backup atomically.
            if FileManager.default.fileExists(atPath: backup.path) {
                try FileManager.default.removeItem(at: backup)
            }
            try FileManager.default.copyItem(at: destination, to: backup)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o600],
                ofItemAtPath: backup.path
            )
        }
        try incoming.save(to: destination)
    }

    private struct StoredIdentity: Codable {
        let privateKeyB64: String

        enum CodingKeys: String, CodingKey {
            case privateKeyB64 = "private_key_b64"
        }
    }
}

public enum HostIdentityError: Error, Equatable {
    case malformed
}
