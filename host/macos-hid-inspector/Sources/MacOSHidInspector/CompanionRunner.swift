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
    private let crossFeedTimeoutSeconds: Double

    init(
        inspector: HidInspector,
        feedback: FeedbackCompanion,
        totalSeconds: Double,
        reportPath: String? = nil,
        crossFeedTimeoutSeconds: Double = 3.0
    ) {
        self.inspector = inspector
        self.feedback = feedback
        self.totalSeconds = totalSeconds
        self.reportPath = reportPath
        self.crossFeedTimeoutSeconds = crossFeedTimeoutSeconds
    }

    func run() -> Int32 {
        print("")
        print("Companion mode: HID watch + BLE feedback writer for \(Int(totalSeconds))s.")
        if let reportPath {
            print("Will write JSON report to \(reportPath) after the run.")
        }

        // Cross-feed pre-probe: spin the run loop briefly until the BLE side
        // resolves first peripheral discovery (or the cross-feed window
        // elapses). If we learn the advertised name before HID enumeration,
        // adopt it as the IOHID name filter so users no longer have to
        // rerun with `--name <phone>` for the phone-named composite case.
        feedback.prepare()
        crossFeedFromBle()

        guard let selected = inspector.discoverWatchTargets() else {
            print("")
            print("No Bluetrack IOHID device matched. Running BLE feedback path on its own; HID watch is skipped.")
            let bleExit = feedback.run()
            let exit = verdict(hidExit: 2, bleExit: bleExit)
            writeReportIfRequested(selected: [], hidExit: 2, bleExit: bleExit)
            return exit
        }

        inspector.beginWatch(selected)
        // Total budget already includes the BLE scan timeout; the cross-feed
        // pre-spin consumed up to `crossFeedTimeoutSeconds` of it. Spin the
        // remainder with a sane minimum so the HID watcher always sees some
        // input window.
        let remaining = max(1.0, totalSeconds - crossFeedTimeoutSeconds)
        CFRunLoopRunInMode(CFRunLoopMode.defaultMode, remaining, false)
        let bleExit = feedback.finish()
        let hidExit = inspector.endWatch()
        let exit = verdict(hidExit: hidExit, bleExit: bleExit)
        writeReportIfRequested(selected: selected, hidExit: hidExit, bleExit: bleExit)
        return exit
    }

    /// Spin the run loop briefly to capture the first BLE scan result, then
    /// apply the cross-feed override to the inspector if appropriate.
    private func crossFeedFromBle() {
        feedback.onFirstScanResult = { _ in
            DispatchQueue.main.async { CFRunLoopStop(CFRunLoopGetMain()) }
        }
        CFRunLoopRunInMode(CFRunLoopMode.defaultMode, crossFeedTimeoutSeconds, false)
        feedback.onFirstScanResult = nil

        let bleName = feedback.discoveredPeripheralName
        if let override = InspectorHints.bleNameToHidFilter(
            blePeripheralName: bleName,
            currentFilter: inspector.effectiveNameFilter
        ) {
            inspector.setNameFilterOverride(override)
            print("Cross-feed: BLE peripheral '\(bleName ?? "")' → IOHID name filter '\(override)'.")
        }
    }

    private func writeReportIfRequested(
        selected: [DeviceSummary],
        hidExit: Int32,
        bleExit: Int32
    ) {
        guard let path = reportPath else { return }
        emitCompanionReport(
            path: path,
            totalSeconds: totalSeconds,
            hid: inspector.snapshot(selected: selected, exitCode: hidExit),
            ble: feedback.snapshot(exitCode: bleExit)
        )
    }

    private func verdict(hidExit: Int32, bleExit: Int32) -> Int32 {
        print("")
        print("Companion verdict:")
        print("  HID watch:    \(label(for: hidExit))")
        print("  BLE feedback: \(label(for: bleExit))")
        if hidExit == 0, bleExit == 0 {
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
