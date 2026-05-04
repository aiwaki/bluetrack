import BluetrackHostKit
import Foundation

/// Lightweight self-check for the BLE feedback crypto contract. Runs without
/// XCTest so this Mac (CommandLineTools, no Xcode) can still validate the
/// crypto path with a single `swift run ... selftest`.
enum FeedbackSelfTest {
    static func run() -> Int32 {
        var passed = 0
        var failed: [String] = []

        check("packet length is 12", &passed, &failed) {
            FeedbackCrypto.buildPacket(counter: 0, dx: 0, dy: 0).count == FeedbackCrypto.frameSize
        }

        check("counter prefix is little-endian", &passed, &failed) {
            let packet = FeedbackCrypto.buildPacket(counter: 0x01020304, dx: 0, dy: 0)
            return Array(packet.prefix(4)) == [0x04, 0x03, 0x02, 0x01]
        }

        let cases: [(UInt32, Float, Float)] = [
            (0, 1.25, -0.75),
            (7, -12.5, 99.125),
            (UInt32.max, 0, 0),
            (42, 127.0, -127.0),
        ]
        for (counter, dx, dy) in cases {
            check("roundtrip counter=\(counter) dx=\(dx) dy=\(dy)", &passed, &failed) {
                let packet = FeedbackCrypto.buildPacket(counter: counter, dx: dx, dy: dy)
                guard let decoded = FeedbackCrypto.decodePacket(packet) else { return false }
                return abs(decoded.dx - dx) < 1e-6 && abs(decoded.dy - dy) < 1e-6
            }
        }

        check("counter changes ciphertext for identical payload", &passed, &failed) {
            let a = FeedbackCrypto.buildPacket(counter: 0, dx: 1.0, dy: 1.0)
            let b = FeedbackCrypto.buildPacket(counter: 1, dx: 1.0, dy: 1.0)
            return Array(a.suffix(from: 4)) != Array(b.suffix(from: 4))
        }

        check("decode rejects wrong length", &passed, &failed) {
            FeedbackCrypto.decodePacket(Data([0, 1, 2])) == nil &&
                FeedbackCrypto.decodePacket(Data(repeating: 0, count: 11)) == nil &&
                FeedbackCrypto.decodePacket(Data(repeating: 0, count: 13)) == nil
        }

        check("UUIDs match the Android contract", &passed, &failed) {
            FeedbackCrypto.serviceUUIDString == "0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263" &&
                FeedbackCrypto.characteristicUUIDString == "4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"
        }

        print("")
        if failed.isEmpty {
            print("FeedbackCrypto self-test: \(passed) checks passed.")
            return 0
        } else {
            print("FeedbackCrypto self-test: \(passed) passed, \(failed.count) failed.")
            for name in failed {
                print("  - \(name)")
            }
            return 7
        }
    }

    private static func check(
        _ name: String,
        _ passed: inout Int,
        _ failed: inout [String],
        _ body: () -> Bool
    ) {
        if body() {
            passed += 1
            print("ok   \(name)")
        } else {
            failed.append(name)
            print("FAIL \(name)")
        }
    }
}
