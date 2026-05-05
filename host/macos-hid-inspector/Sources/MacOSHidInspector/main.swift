import BluetrackHostKit
import Foundation
import IOKit.hid

// Run before any TCC-protected API touches (CoreBluetooth, IOHIDDeviceOpen).
// Re-spawns the process disclaimed from its parent so macOS uses our embedded
// Info.plist for privacy purpose strings instead of the launching app's. Only
// fires once per chain because the disclaimed child sets an env marker.
SelfDisclaim.relaunchIfNeeded()

struct Options {
    enum Command: String {
        case scan
        case watch
        case feedback
        case companion
        case selftest
    }

    var command: Command = .scan
    var nameFilter = "Bluetrack"
    var includeAll = false
    var seconds = 30.0
    var showElements = true
    var showBluetooth = true
    var feedbackDx: Float = 1.25
    var feedbackDy: Float = -0.75
    var feedbackIntervalMs: Int = 5
    var feedbackScanTimeout: Double = 10.0
    var feedbackSeconds: Double = 15.0
    var reportPath: String? = nil
}

struct DeviceSummary {
    let device: IOHIDDevice
    let product: String
    let manufacturer: String
    let transport: String
    let vendorID: Int
    let productID: Int
    let usagePage: Int
    let usage: Int
    let locationID: Int

    var label: String {
        let name = product.isEmpty ? "(unnamed HID device)" : product
        return "\(name) [\(hex(usagePage, width: 2)):\(hex(usage, width: 2)) \(usageName(page: usagePage, usage: usage))]"
    }

    var looksLikeGamepad: Bool {
        usagePage == 0x01 && (usage == 0x04 || usage == 0x05)
    }

    var matchesBluetrack: Bool {
        let haystack = "\(product) \(manufacturer) \(transport)".lowercased()
        return haystack.contains("bluetrack")
    }
}

final class HidInspector {
    private let options: Options
    private var watchedDevices: [IOHIDDevice] = []
    private var lastEventAt = Date.distantPast
    private var eventCount = 0
    private var reportEventCounts: [Int: Int] = [:]
    /// Devices selected by the most recent `discoverWatchTargets()` call.
    /// Surfaced so the standalone `watch` path can build a report after `run()`.
    private(set) var lastSelected: [DeviceSummary] = []

    init(options: Options) {
        self.options = options
    }

    func run() -> Int32 {
        guard let selected = discoverWatchTargets() else {
            lastSelected = []
            return options.command == .watch ? 2 : 0
        }
        lastSelected = selected
        if options.command == .watch {
            return watch(selected)
        }
        return 0
    }

    /// Print device discovery output and return Bluetrack-matching IOHID devices.
    /// Returns nil when no matches were found (the caller decides the exit code).
    func discoverWatchTargets() -> [DeviceSummary]? {
        if options.showBluetooth {
            printBluetoothHints()
        }

        let devices = copyDevices()
        let selected = select(devices)

        print("")
        print("IOHID devices: \(devices.count); selected: \(selected.count)")
        if selected.isEmpty {
            print("No matching IOHID devices. Try `--all`, verify the Mac is paired, and forget/re-pair after descriptor changes.")
            printDeviceCandidates(devices)
            return nil
        }

        for (index, summary) in selected.enumerated() {
            print("")
            printDevice(summary, index: index + 1)
            if options.showElements {
                printElements(summary.device)
            }
        }
        return selected
    }

    private func copyDevices() -> [DeviceSummary] {
        let manager = IOHIDManagerCreate(kCFAllocatorDefault, IOOptionBits(kIOHIDOptionsTypeNone))
        IOHIDManagerSetDeviceMatching(manager, nil)
        let openResult = IOHIDManagerOpen(manager, IOOptionBits(kIOHIDOptionsTypeNone))
        if openResult != kIOReturnSuccess && openResult != kIOReturnExclusiveAccess {
            print("IOHIDManagerOpen warning: \(openResult). Scanning may still work; live watch may need Input Monitoring permission.")
        }

        let rawSet = IOHIDManagerCopyDevices(manager) as? Set<IOHIDDevice> ?? []
        return rawSet.map(DeviceSummary.init(device:)).sorted {
            ($0.product, $0.usagePage, $0.usage, $0.locationID) <
                ($1.product, $1.usagePage, $1.usage, $1.locationID)
        }
    }

    private func select(_ devices: [DeviceSummary]) -> [DeviceSummary] {
        if options.includeAll {
            return devices
        }
        let filter = options.nameFilter.lowercased()
        return devices.filter { summary in
            let haystack = "\(summary.product) \(summary.manufacturer) \(summary.transport)".lowercased()
            return haystack.contains(filter) || isLikelyBluetrackComposite(summary)
        }
    }

    private func printDeviceCandidates(_ devices: [DeviceSummary]) {
        let candidates = devices.filter {
            $0.looksLikeGamepad ||
                $0.matchesBluetrack ||
                isLikelyBluetrackComposite($0) ||
                $0.transport.lowercased().contains("bluetooth")
        }
        if candidates.isEmpty {
            print("No obvious Bluetooth/gamepad HID candidates found.")
            return
        }

        print("")
        print("Nearby candidates:")
        for summary in candidates.prefix(20) {
            print("- \(summary.label), transport=\(summary.transport), manufacturer=\(summary.manufacturer)")
        }
    }

    private func printDevice(_ summary: DeviceSummary, index: Int) {
        print("[\(index)] \(summary.label)")
        print("    manufacturer: \(emptyDash(summary.manufacturer))")
        print("    transport:    \(emptyDash(summary.transport))")
        print("    vendor:       \(hex(summary.vendorID, width: 4))")
        print("    product:      \(hex(summary.productID, width: 4))")
        print("    location:     \(hex(summary.locationID, width: 8))")
        print("    gamepad-like: \(summary.looksLikeGamepad || hasGamepadElements(summary.device) ? "yes" : "no")")
        if isLikelyBluetrackComposite(summary) && !summary.matchesBluetrack {
            print("    note:         matches Bluetrack composite HID shape even though product name differs")
        }
        if let descriptorLength = reportDescriptorLength(summary.device) {
            print("    report map:   \(descriptorLength) bytes")
        }
    }

    private func printElements(_ device: IOHIDDevice) {
        guard let elements = copyElements(device), !elements.isEmpty else {
            print("    elements:     none visible")
            return
        }

        print("    elements:")
        for element in elements.prefix(80) {
            let page = Int(IOHIDElementGetUsagePage(element))
            let usage = Int(IOHIDElementGetUsage(element))
            let reportID = Int(IOHIDElementGetReportID(element))
            let logicalMin = IOHIDElementGetLogicalMin(element)
            let logicalMax = IOHIDElementGetLogicalMax(element)
            let type = elementTypeName(IOHIDElementGetType(element))
            print(
                "      report \(reportID) \(type) " +
                    "\(hex(page, width: 2)):\(hex(usage, width: 2)) \(usageName(page: page, usage: usage)) " +
                    "logical=\(logicalMin)...\(logicalMax)"
            )
        }
        if elements.count > 80 {
            print("      ... \(elements.count - 80) more")
        }
    }

    private func watch(_ summaries: [DeviceSummary]) -> Int32 {
        beginWatch(summaries)
        CFRunLoopRunInMode(CFRunLoopMode.defaultMode, options.seconds, false)
        return endWatch()
    }

    /// Schedule input callbacks for `summaries` on the current run loop.
    /// Pair with `endWatch()` after running the run loop yourself.
    func beginWatch(_ summaries: [DeviceSummary]) {
        print("")
        print("Watching \(summaries.count) device(s) for \(Int(options.seconds))s. Switch Bluetrack to Gamepad and move/press input now.")
        print("If no events appear but elements are listed, grant Terminal Input Monitoring/Accessibility and try again.")

        watchedDevices = summaries.map(\.device)
        for device in watchedDevices {
            let result = IOHIDDeviceOpen(device, IOOptionBits(kIOHIDOptionsTypeNone))
            if result != kIOReturnSuccess && result != kIOReturnExclusiveAccess {
                print("IOHIDDeviceOpen failed for \(stringProperty(device, kIOHIDProductKey)): \(result)")
                continue
            }
            IOHIDDeviceScheduleWithRunLoop(device, CFRunLoopGetCurrent(), CFRunLoopMode.defaultMode.rawValue)
            IOHIDDeviceRegisterInputValueCallback(device, inputCallback, UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()))
        }
    }

    /// Build a JSON-serializable snapshot of the watch outcome. Safe to call
    /// after `endWatch()` because the counters survive teardown.
    func snapshot(selected: [DeviceSummary], exitCode: Int32) -> HidWatchSnapshot {
        HidWatchSnapshot(
            exitCode: exitCode,
            eventCount: eventCount,
            reportEventCounts: Dictionary(
                uniqueKeysWithValues: reportEventCounts.map { (String($0.key), $0.value) }
            ),
            selectedDevices: selected.map { summary in
                EncodableDeviceSummary(
                    product: summary.product,
                    manufacturer: summary.manufacturer,
                    transport: summary.transport,
                    usagePage: summary.usagePage,
                    usage: summary.usage,
                    vendorID: summary.vendorID,
                    productID: summary.productID,
                    locationID: summary.locationID,
                    looksLikeGamepad: summary.looksLikeGamepad
                )
            }
        )
    }

    /// Tear down what `beginWatch` scheduled and print the watch summary.
    /// Returns 0 if any HID events arrived, 3 otherwise.
    func endWatch() -> Int32 {
        for device in watchedDevices {
            IOHIDDeviceUnscheduleFromRunLoop(device, CFRunLoopGetCurrent(), CFRunLoopMode.defaultMode.rawValue)
            IOHIDDeviceClose(device, IOOptionBits(kIOHIDOptionsTypeNone))
        }
        watchedDevices.removeAll()

        print("")
        print("Events captured: \(eventCount)")
        if !reportEventCounts.isEmpty {
            let reportSummary = reportEventCounts
                .sorted { $0.key < $1.key }
                .map { "report \($0.key)=\($0.value)" }
                .joined(separator: ", ")
            print("Report events: \(reportSummary)")
        }
        if (reportEventCounts[2] ?? 0) > 0 {
            print("Gamepad report 2 arrived on macOS. If browser testers still wait, focus on Gamepad API activation or host game-controller mapping.")
        }
        if eventCount == 0 {
            print("No HID input values arrived. That means the problem is below browser Gamepad API: pairing, macOS HID enumeration, or Android HID reports.")
            return 3
        }
        return 0
    }

    fileprivate func handle(value: IOHIDValue) {
        let element = IOHIDValueGetElement(value)
        let page = Int(IOHIDElementGetUsagePage(element))
        let usage = Int(IOHIDElementGetUsage(element))
        let reportID = Int(IOHIDElementGetReportID(element))
        let intValue = IOHIDValueGetIntegerValue(value)
        let now = Date()
        let deltaMs = lastEventAt == Date.distantPast ? 0 : Int(now.timeIntervalSince(lastEventAt) * 1000)
        lastEventAt = now
        eventCount += 1
        reportEventCounts[reportID, default: 0] += 1

        print(
            "event #\(eventCount) +\(deltaMs)ms report=\(reportID) " +
                "\(hex(page, width: 2)):\(hex(usage, width: 2)) \(usageName(page: page, usage: usage)) value=\(intValue)"
        )
        fflush(stdout)
    }

    private func printBluetoothHints() {
        print("Bluetooth hints:")
        let output = runProcess("/usr/sbin/system_profiler", ["SPBluetoothDataType"])
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let filter = options.nameFilter.lowercased()
        var found = false
        for index in lines.indices where lines[index].lowercased().contains(filter) {
            found = true
            let start = max(lines.startIndex, index - 2)
            let end = min(lines.endIndex, index + 8)
            for line in lines[start..<end] {
                print("  \(line)")
            }
            print("")
        }
        if !found {
            print("  No `\(options.nameFilter)` entry found in system_profiler Bluetooth output.")
        }
    }
}

private func isLikelyBluetrackComposite(_ summary: DeviceSummary) -> Bool {
    guard summary.transport.lowercased().contains("bluetooth") else { return false }
    let elements = copyElements(summary.device) ?? []
    let hasMouse = elements.contains { element in
        IOHIDElementGetUsagePage(element) == 0x01 && IOHIDElementGetUsage(element) == 0x02
    }
    return hasMouse && hasGamepadElements(summary.device)
}

private func hasGamepadElements(_ device: IOHIDDevice) -> Bool {
    let elements = copyElements(device) ?? []
    let hasGamePadCollection = elements.contains { element in
        IOHIDElementGetUsagePage(element) == 0x01 && IOHIDElementGetUsage(element) == 0x05
    }
    let hasReportTwoButtons = elements.contains { element in
        IOHIDElementGetReportID(element) == 2 && IOHIDElementGetUsagePage(element) == 0x09
    }
    let hasReportTwoAxes = elements.contains { element in
        IOHIDElementGetReportID(element) == 2 &&
            IOHIDElementGetUsagePage(element) == 0x01 &&
            [0x30, 0x31, 0x32, 0x35, 0x39].contains(Int(IOHIDElementGetUsage(element)))
    }
    return hasGamePadCollection && hasReportTwoButtons && hasReportTwoAxes
}

private let inputCallback: IOHIDValueCallback = { context, _, _, value in
    guard let context else { return }
    let inspector = Unmanaged<HidInspector>.fromOpaque(context).takeUnretainedValue()
    inspector.handle(value: value)
}

private extension DeviceSummary {
    init(device: IOHIDDevice) {
        self.device = device
        product = stringProperty(device, kIOHIDProductKey)
        manufacturer = stringProperty(device, kIOHIDManufacturerKey)
        transport = stringProperty(device, kIOHIDTransportKey)
        vendorID = intProperty(device, kIOHIDVendorIDKey)
        productID = intProperty(device, kIOHIDProductIDKey)
        usagePage = intProperty(device, kIOHIDPrimaryUsagePageKey)
        usage = intProperty(device, kIOHIDPrimaryUsageKey)
        locationID = intProperty(device, kIOHIDLocationIDKey)
    }
}

private func copyElements(_ device: IOHIDDevice) -> [IOHIDElement]? {
    let raw = IOHIDDeviceCopyMatchingElements(device, nil, IOOptionBits(kIOHIDOptionsTypeNone))
    return raw as? [IOHIDElement]
}

private func reportDescriptorLength(_ device: IOHIDDevice) -> Int? {
    guard let data = IOHIDDeviceGetProperty(device, kIOHIDReportDescriptorKey as CFString) as? Data else {
        return nil
    }
    return data.count
}

private func stringProperty(_ device: IOHIDDevice, _ key: String) -> String {
    IOHIDDeviceGetProperty(device, key as CFString) as? String ?? ""
}

private func intProperty(_ device: IOHIDDevice, _ key: String) -> Int {
    if let value = IOHIDDeviceGetProperty(device, key as CFString) as? Int {
        return value
    }
    if let number = IOHIDDeviceGetProperty(device, key as CFString) as? NSNumber {
        return number.intValue
    }
    return 0
}

private func runProcess(_ executable: String, _ arguments: [String]) -> String {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    let pipe = Pipe()
    process.standardOutput = pipe
    process.standardError = Pipe()
    do {
        try process.run()
        process.waitUntilExit()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return String(data: data, encoding: .utf8) ?? ""
    } catch {
        return ""
    }
}

private func parseOptions(_ arguments: [String]) -> Options {
    var options = Options()
    var index = 0
    if let first = arguments.first, let command = Options.Command(rawValue: first) {
        options.command = command
        index = 1
    }

    while index < arguments.count {
        let arg = arguments[index]
        switch arg {
        case "--all":
            options.includeAll = true
        case "--no-elements":
            options.showElements = false
        case "--no-bluetooth":
            options.showBluetooth = false
        case "--name":
            index += 1
            if index < arguments.count {
                options.nameFilter = arguments[index]
            }
        case "--seconds":
            index += 1
            if index < arguments.count, let seconds = Double(arguments[index]) {
                options.seconds = seconds
                options.feedbackSeconds = seconds
            }
        case "--dx":
            index += 1
            if index < arguments.count, let value = Float(arguments[index]) {
                options.feedbackDx = value
            }
        case "--dy":
            index += 1
            if index < arguments.count, let value = Float(arguments[index]) {
                options.feedbackDy = value
            }
        case "--interval-ms":
            index += 1
            if index < arguments.count, let value = Int(arguments[index]) {
                options.feedbackIntervalMs = max(1, value)
            }
        case "--scan-timeout":
            index += 1
            if index < arguments.count, let value = Double(arguments[index]) {
                options.feedbackScanTimeout = value
            }
        case "--report":
            index += 1
            if index < arguments.count {
                options.reportPath = arguments[index]
            }
        case "--help", "-h":
            printUsageAndExit()
        default:
            print("Unknown argument: \(arg)")
            printUsageAndExit(code: 64)
        }
        index += 1
    }
    return options
}

private func printUsageAndExit(code: Int32 = 0) -> Never {
    print("""
    bluetrack-hid-inspector scan|watch|feedback|companion|selftest [options]

    Commands:
      scan               List matching IOHID devices and elements.
      watch              List devices, then print live HID input values.
      feedback           Scan for the Bluetrack BLE feedback service, connect,
                         and write encrypted correction packets.
      companion          Run watch and feedback together on one run loop and
                         print a combined PASS/FAIL verdict for both paths.
      selftest           Run FeedbackCrypto roundtrip checks (no Bluetooth).

    Options (scan/watch):
      --name Bluetrack   Product/manufacturer/transport substring. Default: Bluetrack.
      --all              Show all IOHID devices.
      --seconds 30       Watch duration.
      --no-elements      Hide element listing.
      --no-bluetooth     Skip system_profiler Bluetooth hints.

    Options (feedback):
      --seconds 15       Total write window after the characteristic is ready.
      --scan-timeout 10  Seconds to scan for the BLE feedback advertiser.
      --interval-ms 5    Milliseconds between encrypted writes.
      --dx 1.25          Float dx value to encrypt and send.
      --dy -0.75         Float dy value to encrypt and send.

    Options (watch, feedback, companion):
      --report path.json Write a JSON report of the run to `path.json` after
                         the run finishes (creates or overwrites). For watch
                         the BLE side is recorded as "skipped"; for feedback
                         the HID side is recorded as "skipped".
    """)
    exit(code)
}

private func usageName(page: Int, usage: Int) -> String {
    switch page {
    case 0x01:
        switch usage {
        case 0x02: return "Mouse"
        case 0x04: return "Joystick"
        case 0x05: return "Game Pad"
        case 0x06: return "Keyboard"
        case 0x30: return "X"
        case 0x31: return "Y"
        case 0x32: return "Z"
        case 0x33: return "Rx"
        case 0x34: return "Ry"
        case 0x35: return "Rz"
        case 0x38: return "Wheel"
        case 0x39: return "Hat Switch"
        default: return "GenericDesktop(\(usage))"
        }
    case 0x09:
        return "Button \(usage)"
    case 0x0C:
        return "Consumer(\(usage))"
    default:
        return "Usage(\(page):\(usage))"
    }
}

private func elementTypeName(_ type: IOHIDElementType) -> String {
    switch type {
    case kIOHIDElementTypeInput_Misc: return "input"
    case kIOHIDElementTypeInput_Button: return "button"
    case kIOHIDElementTypeInput_Axis: return "axis"
    case kIOHIDElementTypeInput_ScanCodes: return "scan"
    case kIOHIDElementTypeOutput: return "output"
    case kIOHIDElementTypeFeature: return "feature"
    case kIOHIDElementTypeCollection: return "collection"
    default: return "type\(type.rawValue)"
    }
}

private func hex(_ value: Int, width: Int) -> String {
    "0x" + String(format: "%0\(width)X", value)
}

private func emptyDash(_ value: String) -> String {
    value.isEmpty ? "-" : value
}

let options = parseOptions(Array(CommandLine.arguments.dropFirst()))

switch options.command {
case .scan:
    exit(HidInspector(options: options).run())
case .watch:
    let inspector = HidInspector(options: options)
    let exitCode = inspector.run()
    if let path = options.reportPath {
        emitCompanionReport(
            path: path,
            totalSeconds: options.seconds,
            hid: inspector.snapshot(selected: inspector.lastSelected, exitCode: exitCode),
            ble: BleFeedbackSnapshot.skipped()
        )
    }
    exit(exitCode)
case .feedback:
    let feedback = FeedbackCompanion(options: FeedbackOptions(
        dx: options.feedbackDx,
        dy: options.feedbackDy,
        intervalMs: options.feedbackIntervalMs,
        scanTimeout: options.feedbackScanTimeout,
        seconds: options.feedbackSeconds
    ))
    let exitCode = feedback.run()
    if let path = options.reportPath {
        emitCompanionReport(
            path: path,
            totalSeconds: options.feedbackScanTimeout + options.feedbackSeconds,
            hid: HidWatchSnapshot.skipped(),
            ble: feedback.snapshot(exitCode: exitCode)
        )
    }
    exit(exitCode)
case .companion:
    let inspector = HidInspector(options: options)
    let feedback = FeedbackCompanion(options: FeedbackOptions(
        dx: options.feedbackDx,
        dy: options.feedbackDy,
        intervalMs: options.feedbackIntervalMs,
        scanTimeout: options.feedbackScanTimeout,
        seconds: options.feedbackSeconds
    ))
    let total = options.feedbackScanTimeout + options.feedbackSeconds
    exit(CompanionRunner(
        inspector: inspector,
        feedback: feedback,
        totalSeconds: total,
        reportPath: options.reportPath
    ).run())
case .selftest:
    exit(FeedbackSelfTest.run())
}
