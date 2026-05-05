import BluetrackHostKit
import Foundation

/// Orchestrates `HidInspector.beginWatch` and `FeedbackCompanion.prepare`
/// against a single CFRunLoop session, then prints a combined verdict.
///
/// Both subsystems already register their callbacks against the main run
/// loop and main dispatch queue, so all we have to do is start them, spin
/// the loop for the configured duration, and stop them.
final class CompanionRunner {
    private let inspector: HidInspector
    private let feedback: FeedbackCompanion
    private let totalSeconds: Double
    private let reportPath: String?

    init(
        inspector: HidInspector,
        feedback: FeedbackCompanion,
        totalSeconds: Double,
        reportPath: String? = nil
    ) {
        self.inspector = inspector
        self.feedback = feedback
        self.totalSeconds = totalSeconds
        self.reportPath = reportPath
    }

    func run() -> Int32 {
        print("")
        print("Companion mode: HID watch + BLE feedback writer for \(Int(totalSeconds))s.")
        if let reportPath {
            print("Will write JSON report to \(reportPath) after the run.")
        }

        guard let selected = inspector.discoverWatchTargets() else {
            print("")
            print("No Bluetrack IOHID device matched. Running BLE feedback path on its own; HID watch is skipped.")
            let bleExit = feedback.run()
            let exit = verdict(hidExit: 2, bleExit: bleExit)
            writeReportIfRequested(selected: [], hidExit: 2, bleExit: bleExit)
            return exit
        }

        inspector.beginWatch(selected)
        feedback.prepare()
        CFRunLoopRunInMode(CFRunLoopMode.defaultMode, totalSeconds, false)
        let bleExit = feedback.finish()
        let hidExit = inspector.endWatch()
        let exit = verdict(hidExit: hidExit, bleExit: bleExit)
        writeReportIfRequested(selected: selected, hidExit: hidExit, bleExit: bleExit)
        return exit
    }

    private func writeReportIfRequested(
        selected: [DeviceSummary],
        hidExit: Int32,
        bleExit: Int32
    ) {
        guard let path = reportPath else { return }
        let report = CompanionRunReport(
            tool: CompanionRunReport.toolName,
            toolVersion: CompanionRunReport.toolVersion,
            generatedAt: CompanionReportWriter.iso8601Now(),
            totalSeconds: totalSeconds,
            verdict: CompanionReportWriter.verdict(hidExit: hidExit, bleExit: bleExit),
            hid: inspector.snapshot(selected: selected, exitCode: hidExit),
            ble: feedback.snapshot(exitCode: bleExit)
        )
        do {
            try CompanionReportWriter.write(report, to: path)
            print("")
            print("Companion report written to \(path).")
        } catch {
            let msg = "WARN: failed to write report to \(path): \(error.localizedDescription)\n"
            FileHandle.standardError.write(Data(msg.utf8))
        }
    }

    private func verdict(hidExit: Int32, bleExit: Int32) -> Int32 {
        print("")
        print("Companion verdict:")
        print("  HID watch:    \(label(for: hidExit))")
        print("  BLE feedback: \(label(for: bleExit))")
        if hidExit == 0 && bleExit == 0 {
            print("Both Bluetrack paths look healthy.")
            return 0
        }
        if hidExit == 0 {
            print("HID is healthy. BLE feedback failed; rerun `feedback` for the diagnostic detail.")
        } else if bleExit == 0 {
            print("BLE feedback is healthy. HID side did not arrive; rerun `watch` for the diagnostic detail.")
        } else {
            print("Both paths failed. Check pairing and Bluetrack foreground state, then rerun.")
        }
        return max(hidExit, bleExit)
    }

    private func label(for exit: Int32) -> String {
        exit == 0 ? "PASS" : "FAIL (exit \(exit))"
    }
}
