import BluetrackHostKit
import Foundation

/// Build a `CompanionRunReport` from the available snapshots and persist it
/// to disk. Used by `companion`, `feedback`, and `watch` subcommands so each
/// one can opt into `--report path.json`. Writes go through a shared error
/// path that warns to stderr without aborting the run.
func emitCompanionReport(
    path: String,
    totalSeconds: Double,
    hid: HidWatchSnapshot,
    ble: BleFeedbackSnapshot
) {
    let report = CompanionRunReport(
        tool: CompanionRunReport.toolName,
        toolVersion: CompanionRunReport.toolVersion,
        generatedAt: CompanionReportWriter.iso8601Now(),
        totalSeconds: totalSeconds,
        verdict: CompanionReportWriter.verdict(hidExit: hid.exitCode, bleExit: ble.exitCode),
        hid: hid,
        ble: ble
    )
    do {
        try CompanionReportWriter.write(report, to: path)
        print("")
        print("Report written to \(path).")
    } catch {
        let msg = "WARN: failed to write report to \(path): \(error.localizedDescription)\n"
        FileHandle.standardError.write(Data(msg.utf8))
    }
}
