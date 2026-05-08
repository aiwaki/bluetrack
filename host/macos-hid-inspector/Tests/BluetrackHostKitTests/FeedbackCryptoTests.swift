import XCTest
@testable import BluetrackHostKit

final class FeedbackCryptoTests: XCTestCase {

    private static let testPin = "246810"
    private static let otherPin = "135790"

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
        XCTAssertEqual(FeedbackCrypto.pinMinLength, 4)
        XCTAssertEqual(FeedbackCrypto.pinMaxLength, 12)
    }

    func testFreshSessionExposes32BytePublicKey() {
        let session = FeedbackSession()
        XCTAssertEqual(session.publicKey.count, FeedbackCrypto.publicKeySize)
        XCTAssertFalse(session.isReady, "session must not be ready before deriveSession")
    }

    func testDeriveSessionRejectsInvalidPeerPublicKeyLength() {
        let session = FeedbackSession()
        XCTAssertThrowsError(try session.deriveSession(peerPublicKey: Data(repeating: 0, count: 16), pin: Self.testPin)) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .invalidPeerPublicKey)
        }
    }

    func testDeriveSessionRejectsInvalidPin() {
        let host = FeedbackSession()
        let peer = FeedbackSession().publicKey
        // Too short.
        XCTAssertThrowsError(try host.deriveSession(peerPublicKey: peer, pin: "12")) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .invalidPin)
        }
        // Too long.
        XCTAssertThrowsError(try host.deriveSession(peerPublicKey: peer, pin: "1234567890123")) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .invalidPin)
        }
        // Non-digit.
        XCTAssertThrowsError(try host.deriveSession(peerPublicKey: peer, pin: "12ab56")) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .invalidPin)
        }
        // Empty.
        XCTAssertThrowsError(try host.deriveSession(peerPublicKey: peer, pin: "")) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .invalidPin)
        }
        XCTAssertFalse(host.isReady, "session must remain unready after rejected pin")
    }

    func testNormalizedPinBytesValidation() {
        XCTAssertEqual(FeedbackCrypto.normalizedPinBytes("246810"), Array("246810".utf8))
        XCTAssertEqual(FeedbackCrypto.normalizedPinBytes("  123456  "), Array("123456".utf8), "trims whitespace")
        XCTAssertEqual(FeedbackCrypto.normalizedPinBytes("1234"), Array("1234".utf8))
        XCTAssertEqual(FeedbackCrypto.normalizedPinBytes("123456789012"), Array("123456789012".utf8))
        XCTAssertNil(FeedbackCrypto.normalizedPinBytes("123"), "below minimum length")
        XCTAssertNil(FeedbackCrypto.normalizedPinBytes("1234567890123"), "above maximum length")
        XCTAssertNil(FeedbackCrypto.normalizedPinBytes("12 34"), "internal whitespace not allowed")
        XCTAssertNil(FeedbackCrypto.normalizedPinBytes("12-456"), "hyphen not allowed")
    }

    func testBuildPacketRequiresSession() {
        let session = FeedbackSession()
        XCTAssertThrowsError(try session.buildPacket(counter: 0, dx: 0, dy: 0)) { error in
            XCTAssertEqual(error as? FeedbackSessionError, .sessionNotReady)
        }
    }

    func testHandshakeAndRoundTripRecoversFloats() throws {
        let (host, phone) = try pairedSessions(pin: Self.testPin)
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
        try host.deriveSession(peerPublicKey: phone.publicKey, pin: Self.testPin)

        let first = try host.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
        let second = try host.buildPacket(counter: 1, dx: 1.0, dy: 1.0)
        XCTAssertNotEqual(
            Array(first.suffix(from: FeedbackCrypto.counterPrefixSize)),
            Array(second.suffix(from: FeedbackCrypto.counterPrefixSize)),
            "counter must influence the keystream"
        )
    }

    func testDecodeRejectsWrongLength() throws {
        let (_, phone) = try pairedSessions(pin: Self.testPin)
        XCTAssertNil(try phone.decodePacket(Data([0, 1, 2])))
        XCTAssertNil(try phone.decodePacket(Data(repeating: 0, count: FeedbackCrypto.frameSize - 1)))
        XCTAssertNil(try phone.decodePacket(Data(repeating: 0, count: FeedbackCrypto.frameSize + 1)))
    }

    func testDecodeRejectsTamperedTag() throws {
        let (host, phone) = try pairedSessions(pin: Self.testPin)
        var packet = try host.buildPacket(counter: 5, dx: 0.5, dy: 0.5)
        packet[packet.count - 1] ^= 0x01
        XCTAssertNil(try phone.decodePacket(packet), "AES-GCM must reject tampered tag")
    }

    func testDecodeRejectsWrongCounter() throws {
        let (host, phone) = try pairedSessions(pin: Self.testPin)
        var packet = try host.buildPacket(counter: 5, dx: 0.5, dy: 0.5)
        packet[0] ^= 0x01
        XCTAssertNil(try phone.decodePacket(packet))
    }

    func testDifferentSessionsCannotDecryptEachOther() throws {
        let (hostA, _) = try pairedSessions(pin: Self.testPin)
        let (_, phoneB) = try pairedSessions(pin: Self.testPin)

        let packet = try hostA.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
        XCTAssertNil(try phoneB.decodePacket(packet), "session B must not decrypt session A")
    }

    func testHostWithWrongPinCannotDecryptPhonesFrames() throws {
        // Same X25519 exchange, but the host derives with a different pin —
        // simulates a snoop that sees the public keys but not the pairing
        // pin. AES-GCM tag verification must fail on every frame.
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey, pin: Self.otherPin)
        try phone.deriveSession(peerPublicKey: host.publicKey, pin: Self.testPin)

        let packet = try phone.buildPacket(counter: 1, dx: 9.0, dy: -9.0)
        XCTAssertNil(try host.decodePacket(packet), "wrong pin must not authenticate frames")
    }

    func testPhoneRejectsHostFramesWithWrongPin() throws {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey, pin: Self.otherPin)
        try phone.deriveSession(peerPublicKey: host.publicKey, pin: Self.testPin)

        let packet = try host.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
        XCTAssertNil(try phone.decodePacket(packet), "phone must reject host frames built with the wrong pin")
    }

    func testTwoFreshSessionsHaveDifferentPublicKeys() {
        let a = FeedbackSession()
        let b = FeedbackSession()
        XCTAssertNotEqual(a.publicKey, b.publicKey, "X25519 keys are random per session")
    }

    private func pairedSessions(pin: String) throws -> (FeedbackSession, FeedbackSession) {
        let host = FeedbackSession()
        let phone = FeedbackSession()
        try host.deriveSession(peerPublicKey: phone.publicKey, pin: pin)
        try phone.deriveSession(peerPublicKey: host.publicKey, pin: pin)
        return (host, phone)
    }
}
