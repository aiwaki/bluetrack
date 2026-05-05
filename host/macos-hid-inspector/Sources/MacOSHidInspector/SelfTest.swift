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

        check("companion verdict maps exit codes correctly", &passed, &failed) {
            CompanionReportWriter.verdict(hidExit: 0, bleExit: 0) == "pass" &&
                CompanionReportWriter.verdict(hidExit: 0, bleExit: 4) == "partial" &&
                CompanionReportWriter.verdict(hidExit: 3, bleExit: 0) == "partial" &&
                CompanionReportWriter.verdict(hidExit: 3, bleExit: 4) == "fail"
        }

        check("CompanionRunReport round-trips through JSON", &passed, &failed) {
            let original = sampleReport()
            guard let decoded = try? CompanionReportWriter.roundTrip(original) else { return false }
            return decoded.tool == original.tool &&
                decoded.toolVersion == original.toolVersion &&
                decoded.verdict == original.verdict &&
                decoded.totalSeconds == original.totalSeconds &&
                decoded.hid.exitCode == original.hid.exitCode &&
                decoded.hid.eventCount == original.hid.eventCount &&
                decoded.hid.reportEventCounts == original.hid.reportEventCounts &&
                decoded.hid.selectedDevices.count == original.hid.selectedDevices.count &&
                decoded.ble.exitCode == original.ble.exitCode &&
                decoded.ble.packetsSent == original.ble.packetsSent &&
                decoded.ble.dx == original.ble.dx &&
                decoded.ble.dy == original.ble.dy
        }

        check("encoded report uses sorted keys for diff stability", &passed, &failed) {
            guard let data = try? CompanionReportWriter.encode(sampleReport()),
                  let text = String(data: data, encoding: .utf8) else { return false }
            // The very first key inside the top-level object should be `ble`
            // because sortedKeys orders the dict alphabetically.
            return text.contains("\"ble\" :") || text.contains("\"ble\":")
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

    private static func sampleReport() -> CompanionRunReport {
        CompanionRunReport(
            tool: CompanionRunReport.toolName,
            toolVersion: CompanionRunReport.toolVersion,
            generatedAt: "2026-05-05T00:00:00Z",
            totalSeconds: 25.0,
            verdict: "pass",
            hid: HidWatchSnapshot(
                exitCode: 0,
                eventCount: 1234,
                reportEventCounts: ["1": 800, "2": 434],
                selectedDevices: [
                    EncodableDeviceSummary(
                        product: "aiwaki",
                        manufacturer: "Apple",
                        transport: "Bluetooth",
                        usagePage: 1,
                        usage: 2,
                        vendorID: 0x004C,
                        productID: 0x1234,
                        locationID: 0x1F010000,
                        looksLikeGamepad: false
                    )
                ]
            ),
            ble: BleFeedbackSnapshot(
                exitCode: 0,
                packetsSent: 3000,
                peripheralName: "Bluetrack",
                peripheralIdentifier: "11111111-2222-3333-4444-555555555555",
                scanDurationSeconds: 1.2,
                connectDurationSeconds: 0.4,
                writeWindowSeconds: 15.0,
                dx: 1.25,
                dy: -0.75,
                intervalMs: 5,
                scanTimeoutSeconds: 10.0,
                secondsBudget: 15.0
            )
        )
    }
}
