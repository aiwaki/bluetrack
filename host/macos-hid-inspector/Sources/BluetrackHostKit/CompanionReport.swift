import Foundation

/// Stable on-disk schema for a `companion` (or future `feedback`/`watch`) run.
///
/// The intent is to accumulate one JSON file per run across different
/// Mac+phone combinations so a future hardware compatibility matrix has
/// machine-readable inputs. Field order is forced via JSONEncoder's
/// `.sortedKeys` so checked-in snapshots produce small diffs.
public struct CompanionRunReport: Codable {
    public static let toolName = "bluetrack-hid-inspector"
    public static let toolVersion = "0.3.0" // bump when the schema changes

    public let tool: String
    public let toolVersion: String
    public let generatedAt: String
    public let totalSeconds: Double
    public let verdict: String
    public let hid: HidWatchSnapshot
    public let ble: BleFeedbackSnapshot

    public init(
        tool: String,
        toolVersion: String,
        generatedAt: String,
        totalSeconds: Double,
        verdict: String,
        hid: HidWatchSnapshot,
        ble: BleFeedbackSnapshot
    ) {
        self.tool = tool
        self.toolVersion = toolVersion
        self.generatedAt = generatedAt
        self.totalSeconds = totalSeconds
        self.verdict = verdict
        self.hid = hid
        self.ble = ble
    }
}

public struct HidWatchSnapshot: Codable {
    public let exitCode: Int32
    public let eventCount: Int
    public let reportEventCounts: [String: Int]
    public let selectedDevices: [EncodableDeviceSummary]

    public init(
        exitCode: Int32,
        eventCount: Int,
        reportEventCounts: [String: Int],
        selectedDevices: [EncodableDeviceSummary]
    ) {
        self.exitCode = exitCode
        self.eventCount = eventCount
        self.reportEventCounts = reportEventCounts
        self.selectedDevices = selectedDevices
    }
}

public struct EncodableDeviceSummary: Codable {
    public let product: String
    public let manufacturer: String
    public let transport: String
    public let usagePage: Int
    public let usage: Int
    public let vendorID: Int
    public let productID: Int
    public let locationID: Int
    public let looksLikeGamepad: Bool

    public init(
        product: String,
        manufacturer: String,
        transport: String,
        usagePage: Int,
        usage: Int,
        vendorID: Int,
        productID: Int,
        locationID: Int,
        looksLikeGamepad: Bool
    ) {
        self.product = product
        self.manufacturer = manufacturer
        self.transport = transport
        self.usagePage = usagePage
        self.usage = usage
        self.vendorID = vendorID
        self.productID = productID
        self.locationID = locationID
        self.looksLikeGamepad = looksLikeGamepad
    }
}

public struct BleFeedbackSnapshot: Codable {
    public let exitCode: Int32
    public let packetsSent: Int
    public let peripheralName: String?
    public let peripheralIdentifier: String?
    public let scanDurationSeconds: Double?
    public let connectDurationSeconds: Double?
    public let writeWindowSeconds: Double?
    public let dx: Float
    public let dy: Float
    public let intervalMs: Int
    public let scanTimeoutSeconds: Double
    public let secondsBudget: Double

    public init(
        exitCode: Int32,
        packetsSent: Int,
        peripheralName: String?,
        peripheralIdentifier: String?,
        scanDurationSeconds: Double?,
        connectDurationSeconds: Double?,
        writeWindowSeconds: Double?,
        dx: Float,
        dy: Float,
        intervalMs: Int,
        scanTimeoutSeconds: Double,
        secondsBudget: Double
    ) {
        self.exitCode = exitCode
        self.packetsSent = packetsSent
        self.peripheralName = peripheralName
        self.peripheralIdentifier = peripheralIdentifier
        self.scanDurationSeconds = scanDurationSeconds
        self.connectDurationSeconds = connectDurationSeconds
        self.writeWindowSeconds = writeWindowSeconds
        self.dx = dx
        self.dy = dy
        self.intervalMs = intervalMs
        self.scanTimeoutSeconds = scanTimeoutSeconds
        self.secondsBudget = secondsBudget
    }
}

public enum CompanionReportWriter {
    public static func write(_ report: CompanionRunReport, to path: String) throws {
        let data = try encode(report)
        let url = URL(fileURLWithPath: path)
        try data.write(to: url, options: .atomic)
    }

    public static func encode(_ report: CompanionRunReport) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(report)
    }

    public static func roundTrip(_ report: CompanionRunReport) throws -> CompanionRunReport {
        let data = try encode(report)
        return try JSONDecoder().decode(CompanionRunReport.self, from: data)
    }

    public static func iso8601Now() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: Date())
    }

    /// Map the two exit codes to a single coarse verdict for the report.
    /// "pass" iff both subsystems succeeded; "partial" iff exactly one did.
    public static func verdict(hidExit: Int32, bleExit: Int32) -> String {
        switch (hidExit, bleExit) {
        case (0, 0): return "pass"
        case (0, _), (_, 0): return "partial"
        default: return "fail"
        }
    }
}
