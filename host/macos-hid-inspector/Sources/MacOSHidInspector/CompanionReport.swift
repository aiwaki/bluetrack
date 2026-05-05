import Foundation

/// Stable on-disk schema for a `companion` (or future `feedback`/`watch`) run.
///
/// The intent is to accumulate one JSON file per run across different
/// Mac+phone combinations so a future hardware compatibility matrix has
/// machine-readable inputs. Field order is forced via JSONEncoder's
/// `.sortedKeys` so checked-in snapshots produce small diffs.
struct CompanionRunReport: Codable {
    static let toolName = "bluetrack-hid-inspector"
    static let toolVersion = "0.3.0" // bump when the schema changes

    let tool: String
    let toolVersion: String
    let generatedAt: String
    let totalSeconds: Double
    let verdict: String
    let hid: HidWatchSnapshot
    let ble: BleFeedbackSnapshot
}

struct HidWatchSnapshot: Codable {
    let exitCode: Int32
    let eventCount: Int
    let reportEventCounts: [String: Int]
    let selectedDevices: [EncodableDeviceSummary]
}

struct EncodableDeviceSummary: Codable {
    let product: String
    let manufacturer: String
    let transport: String
    let usagePage: Int
    let usage: Int
    let vendorID: Int
    let productID: Int
    let locationID: Int
    let looksLikeGamepad: Bool
}

struct BleFeedbackSnapshot: Codable {
    let exitCode: Int32
    let packetsSent: Int
    let peripheralName: String?
    let peripheralIdentifier: String?
    let scanDurationSeconds: Double?
    let connectDurationSeconds: Double?
    let writeWindowSeconds: Double?
    let dx: Float
    let dy: Float
    let intervalMs: Int
    let scanTimeoutSeconds: Double
    let secondsBudget: Double
}

enum CompanionReportWriter {
    static func write(_ report: CompanionRunReport, to path: String) throws {
        let data = try encode(report)
        let url = URL(fileURLWithPath: path)
        try data.write(to: url, options: .atomic)
    }

    static func encode(_ report: CompanionRunReport) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(report)
    }

    static func roundTrip(_ report: CompanionRunReport) throws -> CompanionRunReport {
        let data = try encode(report)
        return try JSONDecoder().decode(CompanionRunReport.self, from: data)
    }

    static func iso8601Now() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: Date())
    }

    /// Map the two exit codes to a single coarse verdict for the report.
    /// "pass" iff both subsystems succeeded; "partial" iff exactly one did.
    static func verdict(hidExit: Int32, bleExit: Int32) -> String {
        switch (hidExit, bleExit) {
        case (0, 0): return "pass"
        case (0, _), (_, 0): return "partial"
        default: return "fail"
        }
    }
}
