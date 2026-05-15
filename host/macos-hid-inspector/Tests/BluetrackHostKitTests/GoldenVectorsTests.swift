import CryptoKit
import XCTest
@testable import BluetrackHostKit

/// Loads the cross-platform fixture at `host/test-vectors/feedback_v1.json`
/// and asserts that this Swift implementation derives the same bytes the
/// generator script produced. Catches drift between Swift / Android /
/// Python implementations of the BLE feedback protocol.
final class GoldenVectorsTests: XCTestCase {
    func testGoldenVectorsMatchSwiftImplementation() throws {
        let fixture = try loadFixture()

        // 1. Constants match.
        XCTAssertEqual(fixture.constants.publicKeySize, FeedbackCrypto.publicKeySize)
        XCTAssertEqual(fixture.constants.identityPublicKeySize, FeedbackCrypto.identityPublicKeySize)
        XCTAssertEqual(fixture.constants.identitySignatureSize, FeedbackCrypto.identitySignatureSize)
        XCTAssertEqual(fixture.constants.handshakeWritePayloadSize, FeedbackCrypto.handshakeWritePayloadSize)
        XCTAssertEqual(fixture.constants.nonceSaltSize, FeedbackCrypto.nonceSaltSize)
        XCTAssertEqual(fixture.constants.frameSize, FeedbackCrypto.frameSize)
        XCTAssertEqual(fixture.constants.hkdfSaltUtf8, "bluetrack-feedback-v1")
        XCTAssertEqual(fixture.constants.hkdfInfoBaseUtf8, "aes-256-gcm key+nonce-salt")
        XCTAssertEqual(fixture.constants.pinPrefixUtf8, "|pin:")
        XCTAssertEqual(fixture.constants.nonceSaltSuffixUtf8, "|nonce-salt")

        // 2. UUIDs match.
        XCTAssertEqual(fixture.serviceUUID, FeedbackCrypto.serviceUUIDString)
        XCTAssertEqual(fixture.feedbackCharacteristicUUID, FeedbackCrypto.feedbackCharacteristicUUIDString)
        XCTAssertEqual(fixture.handshakeCharacteristicUUID, FeedbackCrypto.handshakeCharacteristicUUIDString)

        // 3. Re-derive host X25519 + Ed25519 keys from the seeds; pubs
        //    must match the fixture.
        let hostX = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: fixture.host.ephemeralX25519PrivateSeed)
        XCTAssertEqual(
            hostX.publicKey.rawRepresentation,
            fixture.host.ephemeralX25519Public,
            "Swift Curve25519.KeyAgreement.PrivateKey produced a different public key from the same seed"
        )
        let hostId = try Curve25519.Signing.PrivateKey(rawRepresentation: fixture.host.identityEd25519PrivateSeed)
        XCTAssertEqual(
            hostId.publicKey.rawRepresentation,
            fixture.host.identityEd25519Public,
            "Swift Curve25519.Signing.PrivateKey produced a different public key from the same seed"
        )
        let hostIdentity = HostIdentity(signingKey: hostId)
        XCTAssertEqual(hostIdentity.fingerprint(), fixture.host.identityFingerprint)

        // 4. Build the handshake payload via Swift. CryptoKit's Ed25519
        //    signing is HEDGED (intentionally non-deterministic for
        //    side-channel resistance per Apple's design), so the 64-byte
        //    signature bytes will differ from the fixture even though
        //    the input message and key are identical. Verify what we can
        //    deterministically check:
        //      - eph + id_pub segments match the fixture byte-for-byte
        //      - the locally-built signature verifies
        //      - the fixture's own signature verifies under the same
        //        identity public key
        let builtHandshake = try FeedbackHandshakePayload.build(
            ephemeralPublicKey: fixture.host.ephemeralX25519Public,
            hostIdentity: hostIdentity
        )
        XCTAssertEqual(builtHandshake.ephemeralPublicKey, fixture.host.ephemeralX25519Public)
        XCTAssertEqual(builtHandshake.identityPublicKey, fixture.host.identityEd25519Public)
        XCTAssertTrue(
            builtHandshake.verifySignature(),
            "Locally-built Swift handshake must verify against its embedded identity"
        )
        let fixtureHandshake = try XCTUnwrap(
            FeedbackHandshakePayload.parse(fixture.handshakeWritePayload),
            "Fixture handshake payload must parse"
        )
        XCTAssertTrue(
            fixtureHandshake.verifySignature(),
            "Fixture handshake (Python-deterministic Ed25519 sig) must verify under Swift CryptoKit"
        )
        XCTAssertEqual(fixtureHandshake.ephemeralPublicKey, fixture.host.ephemeralX25519Public)
        XCTAssertEqual(fixtureHandshake.identityPublicKey, fixture.host.identityEd25519Public)

        // 5. Phone-side derive: install the host's public key and the
        //    pin into a Swift session whose private key is the fixture
        //    phone seed. The session's symmetric key + nonce salt must
        //    match the fixture.
        let phoneSession = try makeSessionFromSeed(fixture.phone.ephemeralX25519PrivateSeed)
        try phoneSession.deriveSession(
            peerPublicKey: fixture.host.ephemeralX25519Public,
            pin: fixture.pin
        )

        // 6. Round-trip every fixture frame through the phone session.
        XCTAssertEqual(fixture.frames.count, 6)
        for entry in fixture.frames {
            let decoded = try phoneSession.decodePacket(entry.frame)
            XCTAssertNotNil(decoded, "Swift decodePacket returned nil for counter=\(entry.counter)")
            XCTAssertEqual(decoded?.dx ?? 0, entry.dx, accuracy: 1e-6, "dx mismatch counter=\(entry.counter)")
            XCTAssertEqual(decoded?.dy ?? 0, entry.dy, accuracy: 1e-6, "dy mismatch counter=\(entry.counter)")
            // Phone reset between frames so the replay window does not
            // reject our deliberate non-monotonic counter sequence.
            try phoneSession.deriveSession(
                peerPublicKey: fixture.host.ephemeralX25519Public,
                pin: fixture.pin
            )
        }

        // 7. Host-side build: encrypting (counter, dx, dy) with the host
        //    session must produce the same bytes the fixture stored.
        let hostSession = try makeSessionFromSeed(fixture.host.ephemeralX25519PrivateSeed)
        try hostSession.deriveSession(
            peerPublicKey: fixture.phone.ephemeralX25519Public,
            pin: fixture.pin
        )
        for entry in fixture.frames {
            let built = try hostSession.buildPacket(counter: entry.counter, dx: entry.dx, dy: entry.dy)
            XCTAssertEqual(
                built,
                entry.frame,
                "Swift buildPacket bytes diverged from the cross-platform fixture for counter=\(entry.counter)"
            )
        }
    }

    /// Construct a `FeedbackSession` whose ephemeral X25519 key was
    /// generated from `seed` instead of the random `init()`. Reflection
    /// on `FeedbackSession` would be flaky; instead we stamp the seed
    /// into the session via a small `@testable` extension below. Right
    /// now `FeedbackSession.init` always randomises, so we initialise
    /// then overwrite via key-derived equivalence: derive directly via
    /// CryptoKit and call `deriveSession` with the matching peer.
    ///
    /// In practice we just need a session whose `publicKey` matches the
    /// seed-derived key, so the test creates a Swift `FeedbackSession`
    /// that round-trips through `Curve25519.KeyAgreement.PrivateKey
    /// (rawRepresentation:)`. To get that exact key into the session we
    /// add a `@testable` initialiser in this test target alone.
    private func makeSessionFromSeed(_ seed: Data) throws -> FeedbackSession {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: seed)
        return try FeedbackSession(privateKey: priv)
    }

    private func loadFixture() throws -> GoldenFixture {
        let fixtureURL = try locateFixture()
        let data = try Data(contentsOf: fixtureURL)
        return try JSONDecoder().decode(GoldenFixture.self, from: data)
    }

    /// Walk up from this source file (`#file`) to the SwiftPM package
    /// root, then over to `host/test-vectors/feedback_v1.json`. SwiftPM
    /// runs tests with the package source intact so this resolves on
    /// developer machines and CI.
    private func locateFixture() throws -> URL {
        let testFile = URL(fileURLWithPath: #file)
        // Tests/BluetrackHostKitTests/GoldenVectorsTests.swift
        // ../..  → host/macos-hid-inspector/
        // ../../../test-vectors/feedback_v1.json
        let packageRoot = testFile
            .deletingLastPathComponent() // Tests/BluetrackHostKitTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // host/macos-hid-inspector
        let url = packageRoot
            .deletingLastPathComponent() // host
            .appendingPathComponent("test-vectors", isDirectory: true)
            .appendingPathComponent("feedback_v1.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw NSError(
                domain: "GoldenVectorsTests",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "fixture not found at \(url.path)"]
            )
        }
        return url
    }
}

// MARK: - Fixture decoding

private struct GoldenFixture: Decodable {
    let protocolVersion: String
    let serviceUUID: String
    let feedbackCharacteristicUUID: String
    let handshakeCharacteristicUUID: String
    let constants: Constants
    let host: HostKeys
    let phone: PhoneKeys
    let pin: String
    let handshakeWritePayloadB64: String
    let sharedSecretB64: String
    let aesKeyB64: String
    let nonceSaltB64: String
    let frames: [Frame]

    var handshakeWritePayload: Data {
        Data(base64Encoded: handshakeWritePayloadB64)!
    }

    var sharedSecret: Data {
        Data(base64Encoded: sharedSecretB64)!
    }

    var aesKey: Data {
        Data(base64Encoded: aesKeyB64)!
    }

    var nonceSalt: Data {
        Data(base64Encoded: nonceSaltB64)!
    }

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case serviceUUID = "service_uuid"
        case feedbackCharacteristicUUID = "feedback_characteristic_uuid"
        case handshakeCharacteristicUUID = "handshake_characteristic_uuid"
        case constants
        case host
        case phone
        case pin
        case handshakeWritePayloadB64 = "handshake_write_payload_b64"
        case sharedSecretB64 = "shared_secret_b64"
        case aesKeyB64 = "aes_key_b64"
        case nonceSaltB64 = "nonce_salt_b64"
        case frames
    }

    struct Constants: Decodable {
        let publicKeySize: Int
        let identityPublicKeySize: Int
        let identitySignatureSize: Int
        let handshakeWritePayloadSize: Int
        let nonceSaltSize: Int
        let frameSize: Int
        let hkdfSaltUtf8: String
        let hkdfInfoBaseUtf8: String
        let pinPrefixUtf8: String
        let nonceSaltSuffixUtf8: String

        enum CodingKeys: String, CodingKey {
            case publicKeySize = "public_key_size"
            case identityPublicKeySize = "identity_public_key_size"
            case identitySignatureSize = "identity_signature_size"
            case handshakeWritePayloadSize = "handshake_write_payload_size"
            case nonceSaltSize = "nonce_salt_size"
            case frameSize = "frame_size"
            case hkdfSaltUtf8 = "hkdf_salt_utf8"
            case hkdfInfoBaseUtf8 = "hkdf_info_base_utf8"
            case pinPrefixUtf8 = "pin_prefix_utf8"
            case nonceSaltSuffixUtf8 = "nonce_salt_suffix_utf8"
        }
    }

    struct HostKeys: Decodable {
        let ephemeralX25519PrivateSeedB64: String
        let ephemeralX25519PublicB64: String
        let identityEd25519PrivateSeedB64: String
        let identityEd25519PublicB64: String
        let identityFingerprint: String

        var ephemeralX25519PrivateSeed: Data {
            Data(base64Encoded: ephemeralX25519PrivateSeedB64)!
        }

        var ephemeralX25519Public: Data {
            Data(base64Encoded: ephemeralX25519PublicB64)!
        }

        var identityEd25519PrivateSeed: Data {
            Data(base64Encoded: identityEd25519PrivateSeedB64)!
        }

        var identityEd25519Public: Data {
            Data(base64Encoded: identityEd25519PublicB64)!
        }

        enum CodingKeys: String, CodingKey {
            case ephemeralX25519PrivateSeedB64 = "ephemeral_x25519_private_seed_b64"
            case ephemeralX25519PublicB64 = "ephemeral_x25519_public_b64"
            case identityEd25519PrivateSeedB64 = "identity_ed25519_private_seed_b64"
            case identityEd25519PublicB64 = "identity_ed25519_public_b64"
            case identityFingerprint = "identity_fingerprint"
        }
    }

    struct PhoneKeys: Decodable {
        let ephemeralX25519PrivateSeedB64: String
        let ephemeralX25519PublicB64: String

        var ephemeralX25519PrivateSeed: Data {
            Data(base64Encoded: ephemeralX25519PrivateSeedB64)!
        }

        var ephemeralX25519Public: Data {
            Data(base64Encoded: ephemeralX25519PublicB64)!
        }

        enum CodingKeys: String, CodingKey {
            case ephemeralX25519PrivateSeedB64 = "ephemeral_x25519_private_seed_b64"
            case ephemeralX25519PublicB64 = "ephemeral_x25519_public_b64"
        }
    }

    struct Frame: Decodable {
        let counter: UInt32
        let dx: Float
        let dy: Float
        let frameB64: String

        var frame: Data {
            Data(base64Encoded: frameB64)!
        }

        enum CodingKeys: String, CodingKey {
            case counter
            case dx
            case dy
            case frameB64 = "frame_b64"
        }
    }
}
