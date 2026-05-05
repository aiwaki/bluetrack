import XCTest
@testable import BluetrackHostKit

final class FeedbackCryptoTests: XCTestCase {

    func testPacketLengthAndCounterPrefix() throws {
        let packet = FeedbackCrypto.buildPacket(counter: 0x01020304, dx: 1.25, dy: -0.75)

        XCTAssertEqual(packet.count, FeedbackCrypto.frameSize)
        XCTAssertEqual(Array(packet.prefix(4)), [0x04, 0x03, 0x02, 0x01], "counter is little-endian")
    }

    func testRoundTripRecoversFloatsForRepresentativeCases() throws {
        let cases: [(UInt32, Float, Float)] = [
            (0, 1.25, -0.75),
            (7, -12.5, 99.125),
            (UInt32.max, 0, 0),
            (42, 127.0, -127.0),
            (0xDEAD_BEEF, .infinity, -.infinity),
        ]
        for (counter, dx, dy) in cases {
            let packet = FeedbackCrypto.buildPacket(counter: counter, dx: dx, dy: dy)
            let decoded = try XCTUnwrap(
                FeedbackCrypto.decodePacket(packet),
                "decode failed for counter=\(counter)"
            )
            if dx.isFinite {
                XCTAssertEqual(decoded.dx, dx, accuracy: 1e-6, "dx mismatch counter=\(counter)")
            } else {
                XCTAssertEqual(decoded.dx, dx, "dx mismatch (non-finite) counter=\(counter)")
            }
            if dy.isFinite {
                XCTAssertEqual(decoded.dy, dy, accuracy: 1e-6, "dy mismatch counter=\(counter)")
            } else {
                XCTAssertEqual(decoded.dy, dy, "dy mismatch (non-finite) counter=\(counter)")
            }
        }
    }

    func testCounterChangesCiphertextForSamePayload() throws {
        let first = FeedbackCrypto.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
        let second = FeedbackCrypto.buildPacket(counter: 1, dx: 1.0, dy: 1.0)

        XCTAssertNotEqual(
            Array(first.suffix(from: 4)),
            Array(second.suffix(from: 4)),
            "counter must influence the keystream"
        )
    }

    func testDecodeRejectsWrongLength() {
        XCTAssertNil(FeedbackCrypto.decodePacket(Data([0, 1, 2])))
        XCTAssertNil(FeedbackCrypto.decodePacket(Data(repeating: 0, count: 11)))
        XCTAssertNil(FeedbackCrypto.decodePacket(Data(repeating: 0, count: 13)))
    }

    func testServiceAndCharacteristicUUIDsMatchAndroidContract() {
        XCTAssertEqual(FeedbackCrypto.serviceUUIDString, "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263")
        XCTAssertEqual(FeedbackCrypto.characteristicUUIDString, "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6")
    }
}
