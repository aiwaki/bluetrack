import XCTest
@testable import BluetrackHostKit

final class FeedbackHandshakeTests: XCTestCase {

    func testHandshakeWritePayloadSizeIs128() {
        XCTAssertEqual(FeedbackCrypto.handshakeWritePayloadSize, 128)
        XCTAssertEqual(FeedbackCrypto.identityPublicKeySize, 32)
        XCTAssertEqual(FeedbackCrypto.identitySignatureSize, 64)
    }

    func testBuildAndVerifyRoundTrip() throws {
        let id = HostIdentity()
        let session = FeedbackSession()
        let payload = try FeedbackHandshakePayload.build(
            ephemeralPublicKey: session.publicKey,
            hostIdentity: id
        )
        XCTAssertEqual(payload.ephemeralPublicKey, session.publicKey)
        XCTAssertEqual(payload.identityPublicKey, id.publicKeyBytes)
        XCTAssertEqual(payload.signature.count, FeedbackCrypto.identitySignatureSize)
        XCTAssertTrue(payload.verifySignature())
    }

    func testEncodedDecodesBackToSamePayload() throws {
        let id = HostIdentity()
        let session = FeedbackSession()
        let original = try FeedbackHandshakePayload.build(
            ephemeralPublicKey: session.publicKey,
            hostIdentity: id
        )
        let encoded = original.encoded()
        XCTAssertEqual(encoded.count, FeedbackCrypto.handshakeWritePayloadSize)
        let decoded = try XCTUnwrap(FeedbackHandshakePayload.parse(encoded))
        XCTAssertEqual(decoded, original)
        XCTAssertTrue(decoded.verifySignature())
    }

    func testParseRejectsWrongLength() {
        XCTAssertNil(FeedbackHandshakePayload.parse(Data(repeating: 0, count: 127)))
        XCTAssertNil(FeedbackHandshakePayload.parse(Data(repeating: 0, count: 129)))
        XCTAssertNil(FeedbackHandshakePayload.parse(Data()))
    }

    func testVerifyFailsWhenEphemeralIsTampered() throws {
        let id = HostIdentity()
        let session = FeedbackSession()
        let payload = try FeedbackHandshakePayload.build(
            ephemeralPublicKey: session.publicKey,
            hostIdentity: id
        )
        var tamperedEph = payload.ephemeralPublicKey
        tamperedEph[0] ^= 0x01
        let tampered = FeedbackHandshakePayload(
            ephemeralPublicKey: tamperedEph,
            identityPublicKey: payload.identityPublicKey,
            signature: payload.signature
        )
        XCTAssertFalse(tampered.verifySignature())
    }

    func testVerifyFailsWhenSignatureIsTampered() throws {
        let id = HostIdentity()
        let session = FeedbackSession()
        let payload = try FeedbackHandshakePayload.build(
            ephemeralPublicKey: session.publicKey,
            hostIdentity: id
        )
        var tamperedSig = payload.signature
        tamperedSig[0] ^= 0x01
        let tampered = FeedbackHandshakePayload(
            ephemeralPublicKey: payload.ephemeralPublicKey,
            identityPublicKey: payload.identityPublicKey,
            signature: tamperedSig
        )
        XCTAssertFalse(tampered.verifySignature())
    }

    func testVerifyFailsWhenIdentityIsSubstituted() throws {
        let realId = HostIdentity()
        let attackerId = HostIdentity()
        let session = FeedbackSession()
        let real = try FeedbackHandshakePayload.build(
            ephemeralPublicKey: session.publicKey,
            hostIdentity: realId
        )
        // Same eph + sig but the attacker's identity pubkey: signature
        // verification must fail because the sig was made by realId's key.
        let substituted = FeedbackHandshakePayload(
            ephemeralPublicKey: real.ephemeralPublicKey,
            identityPublicKey: attackerId.publicKeyBytes,
            signature: real.signature
        )
        XCTAssertFalse(substituted.verifySignature())
    }

    func testBuildRejectsWrongLengthEphemeralKey() {
        let id = HostIdentity()
        XCTAssertThrowsError(
            try FeedbackHandshakePayload.build(
                ephemeralPublicKey: Data(repeating: 0, count: 16),
                hostIdentity: id
            )
        ) { error in
            XCTAssertEqual(error as? FeedbackHandshakeError, .invalidEphemeralKey)
        }
    }
}
