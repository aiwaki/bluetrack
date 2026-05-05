import XCTest
@testable import BluetrackHostKit

final class CompanionReportTests: XCTestCase {

    func testVerdictMappingForAllExitCodeCombinations() {
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: 0, bleExit: 0), "pass")
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: 0, bleExit: 4), "partial")
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: 3, bleExit: 0), "partial")
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: 3, bleExit: 4), "fail")
    }

    func testVerdictTreatsSkippedSidesAsNeutral() {
        let skipped = CompanionReportWriter.skippedExitCode

        // Both skipped: no run.
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: skipped, bleExit: skipped), "skipped")

        // One side skipped, the other passes -> overall pass.
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: skipped, bleExit: 0), "pass")
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: 0, bleExit: skipped), "pass")

        // One side skipped, the other fails -> overall fail (the only side
        // that actually ran reports failure).
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: skipped, bleExit: 4), "fail")
        XCTAssertEqual(CompanionReportWriter.verdict(hidExit: 3, bleExit: skipped), "fail")
    }

    func testSkippedFactoriesAreRoundTripStable() throws {
        let report = CompanionRunReport(
            tool: CompanionRunReport.toolName,
            toolVersion: CompanionRunReport.toolVersion,
            generatedAt: "2026-05-05T00:00:00Z",
            totalSeconds: 1.0,
            verdict: "pass",
            hid: HidWatchSnapshot.skipped(),
            ble: BleFeedbackSnapshot(
                exitCode: 0,
                packetsSent: 100,
                peripheralName: "Bluetrack",
                peripheralIdentifier: nil,
                scanDurationSeconds: nil,
                connectDurationSeconds: nil,
                writeWindowSeconds: 1.0,
                dx: 1.0, dy: 1.0,
                intervalMs: 5,
                scanTimeoutSeconds: 10.0,
                secondsBudget: 1.0
            )
        )

        let decoded = try CompanionReportWriter.roundTrip(report)

        XCTAssertEqual(decoded.hid.exitCode, CompanionReportWriter.skippedExitCode)
        XCTAssertTrue(decoded.hid.selectedDevices.isEmpty)
        XCTAssertEqual(decoded.hid.eventCount, 0)
        XCTAssertEqual(decoded.ble.packetsSent, 100)
    }

    func testReportRoundTripsThroughJSON() throws {
        let original = sampleReport()
        let decoded = try CompanionReportWriter.roundTrip(original)

        XCTAssertEqual(decoded.tool, original.tool)
        XCTAssertEqual(decoded.toolVersion, original.toolVersion)
        XCTAssertEqual(decoded.generatedAt, original.generatedAt)
        XCTAssertEqual(decoded.totalSeconds, original.totalSeconds)
        XCTAssertEqual(decoded.verdict, original.verdict)

        XCTAssertEqual(decoded.hid.exitCode, original.hid.exitCode)
        XCTAssertEqual(decoded.hid.eventCount, original.hid.eventCount)
        XCTAssertEqual(decoded.hid.reportEventCounts, original.hid.reportEventCounts)
        XCTAssertEqual(decoded.hid.selectedDevices.count, original.hid.selectedDevices.count)
        XCTAssertEqual(decoded.hid.selectedDevices.first?.product, "aiwaki")

        XCTAssertEqual(decoded.ble.exitCode, original.ble.exitCode)
        XCTAssertEqual(decoded.ble.packetsSent, original.ble.packetsSent)
        XCTAssertEqual(decoded.ble.peripheralName, original.ble.peripheralName)
        XCTAssertEqual(decoded.ble.peripheralIdentifier, original.ble.peripheralIdentifier)
        XCTAssertEqual(decoded.ble.dx, original.ble.dx)
        XCTAssertEqual(decoded.ble.dy, original.ble.dy)
        XCTAssertEqual(decoded.ble.intervalMs, original.ble.intervalMs)
    }

    func testEncodedReportUsesSortedKeysForDiffStability() throws {
        let data = try CompanionReportWriter.encode(sampleReport())
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))

        // Top-level keys must appear alphabetically: ble, generatedAt, hid,
        // tool, toolVersion, totalSeconds, verdict.
        let bleIdx = try XCTUnwrap(text.range(of: "\"ble\"")?.lowerBound)
        let hidIdx = try XCTUnwrap(text.range(of: "\"hid\"")?.lowerBound)
        let toolIdx = try XCTUnwrap(text.range(of: "\"tool\"")?.lowerBound)
        let verdictIdx = try XCTUnwrap(text.range(of: "\"verdict\"")?.lowerBound)
        XCTAssertLessThan(bleIdx, hidIdx)
        XCTAssertLessThan(hidIdx, toolIdx)
        XCTAssertLessThan(toolIdx, verdictIdx)
    }

    func testOptionalBleTimingsSurviveNullEncoding() throws {
        let report = CompanionRunReport(
            tool: CompanionRunReport.toolName,
            toolVersion: CompanionRunReport.toolVersion,
            generatedAt: "2026-05-05T00:00:00Z",
            totalSeconds: 1.0,
            verdict: "fail",
            hid: HidWatchSnapshot(exitCode: 3, eventCount: 0, reportEventCounts: [:], selectedDevices: []),
            ble: BleFeedbackSnapshot(
                exitCode: 4,
                packetsSent: 0,
                peripheralName: nil,
                peripheralIdentifier: nil,
                scanDurationSeconds: nil,
                connectDurationSeconds: nil,
                writeWindowSeconds: nil,
                dx: 0, dy: 0,
                intervalMs: 5,
                scanTimeoutSeconds: 10.0,
                secondsBudget: 15.0
            )
        )

        let decoded = try CompanionReportWriter.roundTrip(report)

        XCTAssertNil(decoded.ble.peripheralName)
        XCTAssertNil(decoded.ble.scanDurationSeconds)
        XCTAssertNil(decoded.ble.writeWindowSeconds)
    }

    private func sampleReport() -> CompanionRunReport {
        CompanionRunReport(
            tool: CompanionRunReport.toolName,
            toolVersion: CompanionRunReport.toolVersion,
            generatedAt: "2026-05-05T12:34:56Z",
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
