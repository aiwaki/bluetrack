import XCTest
@testable import BluetrackHostKit

final class FeedbackCryptoTests: XCTestCase {

    func testServiceAndCharacteristicUUIDsMatchAndroidContract() {
        XCTAssertEqual(
            FeedbackCrypto.serviceUUIDString,
            "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"
        )
        XCTAssertEqual(
            FeedbackCrypto.feedbackCharacteristicUUIDString,
            "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
        )
        XCTAssertEqual(
            FeedbackCrypto.handshakeCharacteristicUUIDString,
            "4846ff88-f2d4-4df2-9500-9bf8ed23f9e6"
        )
        XCTAssertEqual(FeedbackCrypto.frameSize, 28)
        XCTAssertEqual(FeedbackCrypto.publicKeySize, 32)
        XCTAssertEqual(FeedbackCrypto.tagSize, 16)
        XCTAssertEqual(FeedbackCrypto.plaintextSize, 8)
    }

    func testFreshSessionExposes32BytePublicKey() {
        let session = FeedbackSession()
        XCTAssertEqual(session.publicKey.count, FeedbackCrypto.publicKeySize)
        XCTAssertFalse(session.isReady, "session must not be ready before deriveSession")
    }

    func testDeriveSessionRejectsInvalidPeerPublicKeyLength() {
        let session = FeedbackSession()
        XCTAssertThrowsError(try session.deriveSession(peerPublicKey: Data(repeating: 0, count: 16))) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .invalidPeerPublicKey)
        }
    }

    func testBuildPacketRequiresSession() {
        let session = FeedbackSession()
        XCTAssertThrowsError(try session.buildPacket(counter: 0, dx: 0, dy: 0)) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .sessionNotReady)
        }
    }

    func testHandshakeAndRoundTripRecoversFloats() throws {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey)
        try phone.deriveSession(peerPublicKey: host.publicKey)
        XCTAssertTrue(host.isReady)
        XCTAssertTrue(phone.isReady)

        let cases: [(UInt32, Float, Float)] = [
            (0, 1.25, -0.75),
            (7, -12.5, 99.125),
            (UInt32.max, 0, 0),
            (42, 127.0, -127.0),
        ]
        for (counter, dx, dy) in cases {
            let packet = try host.buildPacket(counter: counter, dx: dx, dy: dy)
            XCTAssertEqual(packet.count, FeedbackCrypto.frameSize)
            let counterBytes = Array(packet.prefix(4))
            XCTAssertEqual(
                counterBytes,
                [
                    UInt8(counter & 0xFF),
                    UInt8((counter >> 8) & 0xFF),
                    UInt8((counter >> 16) & 0xFF),
                    UInt8((counter >> 24) & 0xFF),
                ],
                "counter prefix is little-endian"
            )
            let decoded = try XCTUnwrap(try phone.decodePacket(packet), "decode failed counter=\(counter)")
            XCTAssertEqual(decoded.dx, dx, accuracy: 1e-6, "dx mismatch counter=\(counter)")
            XCTAssertEqual(decoded.dy, dy, accuracy: 1e-6, "dy mismatch counter=\(counter)")
        }
    }

    func testCounterChangesCiphertextForSamePayload() throws {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey)

        let first = try host.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
        let second = try host.buildPacket(counter: 1, dx: 1.0, dy: 1.0)
        XCTAssertNotEqual(
            Array(first.suffix(from: FeedbackCrypto.counterPrefixSize)),
            Array(second.suffix(from: FeedbackCrypto.counterPrefixSize)),
            "counter must influence the keystream"
        )
    }

    func testDecodeRejectsWrongLength() throws {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey)
        try phone.deriveSession(peerPublicKey: host.publicKey)
        XCTAssertNil(try phone.decodePacket(Data([0, 1, 2])))
        XCTAssertNil(try phone.decodePacket(Data(repeating: 0, count: FeedbackCrypto.frameSize - 1)))
        XCTAssertNil(try phone.decodePacket(Data(repeating: 0, count: FeedbackCrypto.frameSize + 1)))
    }

    func testDecodeRejectsTamperedTag() throws {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey)
        try phone.deriveSession(peerPublicKey: host.publicKey)

        var packet = try host.buildPacket(counter: 5, dx: 0.5, dy: 0.5)
        // Flip a bit in the auth tag (last byte).
        packet[packet.count - 1] ^= 0x01
        XCTAssertNil(try phone.decodePacket(packet), "AES-GCM must reject tampered tag")
    }

    func testDecodeRejectsWrongCounter() throws {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey)
        try phone.deriveSession(peerPublicKey: host.publicKey)

        var packet = try host.buildPacket(counter: 5, dx: 0.5, dy: 0.5)
        // Bump the counter prefix without re-encrypting; AES-GCM nonce
        // mismatch should make the tag invalid.
        packet[0] ^= 0x01
        XCTAssertNil(try phone.decodePacket(packet))
    }

    func testDifferentSessionsCannotDecryptEachOther() throws {
        let hostA = FeedbackSession()
        let phoneA = FeedbackSession()
        try hostA.deriveSession(peerPublicKey: phoneA.publicKey)
        try phoneA.deriveSession(peerPublicKey: hostA.publicKey)

        let hostB = FeedbackSession()
        let phoneB = FeedbackSession()
        try hostB.deriveSession(peerPublicKey: phoneB.publicKey)
        try phoneB.deriveSession(peerPublicKey: hostB.publicKey)

        let packet = try hostA.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
        XCTAssertNil(try phoneB.decodePacket(packet), "session B must not decrypt session A")
    }

    func testTwoFreshSessionsHaveDifferentPublicKeys() {
        let a = FeedbackSession()
        let b = FeedbackSession()
        XCTAssertNotEqual(a.publicKey, b.publicKey, "X25519 keys are random per session")
    }
}
