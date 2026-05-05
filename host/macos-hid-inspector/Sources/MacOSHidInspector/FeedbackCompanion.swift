import BluetrackHostKit
import CoreBluetooth
import Foundation

struct FeedbackOptions {
    var dx: Float = 1.25
    var dy: Float = -0.75
    var intervalMs: Int = 5
    var scanTimeout: Double = 10.0
    var seconds: Double = 15.0
}

final class FeedbackCompanion: NSObject {
    private let options: FeedbackOptions
    private let serviceUUID = CBUUID(string: FeedbackCrypto.serviceUUIDString)
    private let characteristicUUID = CBUUID(string: FeedbackCrypto.characteristicUUIDString)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristic: CBCharacteristic?
    private var counter: UInt32 = 0
    private var packetsSent = 0
    private var sendTimer: DispatchSourceTimer?
    private var scanDeadline: DispatchWorkItem?
    private var scanStartedAt: Date?
    private var connectedAt: Date?
    private var charReadyAt: Date?
    private var scanFailed = false

    init(options: FeedbackOptions) {
        self.options = options
        super.init()
        self.central = CBCentralManager(delegate: self, queue: nil)
    }

    func run() -> Int32 {
        prepare()
        let totalSeconds = options.scanTimeout + options.seconds
        CFRunLoopRunInMode(CFRunLoopMode.defaultMode, totalSeconds, false)
        return finish()
    }

    /// Print the heading. Scanning is already in flight from `init` because
    /// `CBCentralManager` begins async setup before any run loop spins.
    func prepare() {
        print(
            "Feedback writer: scan timeout \(Int(options.scanTimeout))s, write \(options.intervalMs)ms cadence " +
                "for up to \(Int(options.seconds))s, dx=\(options.dx) dy=\(options.dy)."
        )
    }

    /// Tear down BLE state and print a summary. Returns the standalone
    /// feedback exit code (0 on success, non-zero with diagnosis).
    func finish() -> Int32 {
        teardown()
        return summarize()
    }

    /// Build a JSON-serializable snapshot of the BLE side. Call after `finish()`
    /// so timings reflect the full run.
    func snapshot(exitCode: Int32) -> BleFeedbackSnapshot {
        let scanDuration: Double?
        if let scanStartedAt {
            // Prefer the moment of advertiser discovery (connectedAt-ish),
            // falling back to scan deadline if scan never produced one.
            if let connectedAt {
                scanDuration = connectedAt.timeIntervalSince(scanStartedAt)
            } else if scanFailed {
                scanDuration = options.scanTimeout
            } else {
                scanDuration = nil
            }
        } else {
            scanDuration = nil
        }
        let connectDuration: Double?
        if let connectedAt, let charReadyAt {
            connectDuration = charReadyAt.timeIntervalSince(connectedAt)
        } else {
            connectDuration = nil
        }
        let writeDuration: Double?
        if let charReadyAt {
            writeDuration = Date().timeIntervalSince(charReadyAt)
        } else {
            writeDuration = nil
        }
        return BleFeedbackSnapshot(
            exitCode: exitCode,
            packetsSent: packetsSent,
            peripheralName: peripheral?.name,
            peripheralIdentifier: peripheral?.identifier.uuidString,
            scanDurationSeconds: scanDuration,
            connectDurationSeconds: connectDuration,
            writeWindowSeconds: writeDuration,
            dx: options.dx,
            dy: options.dy,
            intervalMs: options.intervalMs,
            scanTimeoutSeconds: options.scanTimeout,
            secondsBudget: options.seconds
        )
    }

    private func teardown() {
        sendTimer?.cancel()
        sendTimer = nil
        scanDeadline?.cancel()
        scanDeadline = nil
        if let peripheral, peripheral.state == .connected || peripheral.state == .connecting {
            central.cancelPeripheralConnection(peripheral)
        }
        if central.isScanning {
            central.stopScan()
        }
    }

    private func summarize() -> Int32 {
        print("")
        if central.state != .poweredOn {
            print("Bluetooth state: \(stateLabel(central.state)). Cannot run without poweredOn.")
            return 4
        }
        if peripheral == nil {
            if scanFailed {
                return 4
            }
            print("No Bluetrack feedback advertiser found within \(Int(options.scanTimeout))s.")
            print("Hints: open Bluetrack on the phone, confirm the BLE feedback service is advertising, and grant Terminal Bluetooth permission in System Settings.")
            return 4
        }
        if characteristic == nil {
            print("Peripheral connected but characteristic \(FeedbackCrypto.characteristicUUIDString) was not discovered.")
            return 5
        }
        print("Wrote \(packetsSent) feedback packets.")
        if packetsSent == 0 {
            print("No packets were written even though the characteristic was ready. The send timer never fired or seconds was too short.")
            return 6
        }
        if let charReadyAt {
            let writeWindow = Date().timeIntervalSince(charReadyAt)
            print("Write window: \(String(format: "%.1f", writeWindow))s. If the Android timeline shows feedback packets, the BLE path is healthy.")
        }
        return 0
    }

    private func stopRunLoopSoon() {
        DispatchQueue.main.async { CFRunLoopStop(CFRunLoopGetMain()) }
    }

    private func stateLabel(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOn: return "poweredOn"
        case .poweredOff: return "poweredOff"
        case .unauthorized: return "unauthorized"
        case .unsupported: return "unsupported"
        case .resetting: return "resetting"
        case .unknown: return "unknown"
        @unknown default: return "other"
        }
    }
}

extension FeedbackCompanion: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            scanStartedAt = Date()
            print("Bluetooth ready. Scanning for service \(FeedbackCrypto.serviceUUIDString)...")
            central.scanForPeripherals(withServices: [serviceUUID], options: nil)
            let deadline = DispatchWorkItem { [weak self] in
                guard let self else { return }
                if self.peripheral == nil {
                    self.scanFailed = true
                    print("Scan timeout (\(Int(self.options.scanTimeout))s) elapsed without advertisers.")
                    self.central.stopScan()
                    self.stopRunLoopSoon()
                }
            }
            scanDeadline = deadline
            DispatchQueue.main.asyncAfter(deadline: .now() + options.scanTimeout, execute: deadline)
        case .poweredOff:
            print("Bluetooth is off. Enable it and rerun.")
            stopRunLoopSoon()
        case .unauthorized:
            print("Bluetooth permission denied for this process. Grant Terminal access in System Settings → Privacy & Security → Bluetooth.")
            stopRunLoopSoon()
        case .unsupported:
            print("This Mac does not support Bluetooth LE.")
            stopRunLoopSoon()
        case .resetting, .unknown:
            print("Bluetooth state \(stateLabel(central.state)); waiting...")
        @unknown default:
            print("Bluetooth state @unknown; aborting.")
            stopRunLoopSoon()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard self.peripheral == nil else { return }
        let scanMs: Int
        if let started = scanStartedAt {
            scanMs = Int(Date().timeIntervalSince(started) * 1000)
        } else {
            scanMs = 0
        }
        let name = peripheral.name ?? "(unnamed)"
        print("Found \(name) at \(peripheral.identifier) after \(scanMs)ms, RSSI \(RSSI). Connecting...")
        scanDeadline?.cancel()
        self.peripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectedAt = Date()
        print("Connected. Discovering service...")
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        print("Connection failed: \(error?.localizedDescription ?? "unknown error").")
        stopRunLoopSoon()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let error {
            print("Disconnected: \(error.localizedDescription).")
        }
    }
}

extension FeedbackCompanion: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            print("Service discovery failed: \(error.localizedDescription).")
            stopRunLoopSoon()
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            print("Peripheral does not expose service \(FeedbackCrypto.serviceUUIDString).")
            stopRunLoopSoon()
            return
        }
        peripheral.discoverCharacteristics([characteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error {
            print("Characteristic discovery failed: \(error.localizedDescription).")
            stopRunLoopSoon()
            return
        }
        guard let char = service.characteristics?.first(where: { $0.uuid == characteristicUUID }) else {
            print("Service did not expose characteristic \(FeedbackCrypto.characteristicUUIDString).")
            stopRunLoopSoon()
            return
        }
        characteristic = char
        charReadyAt = Date()
        print("Characteristic ready. Writing every \(options.intervalMs)ms.")
        startSendTimer()
        scheduleWriteWindowEnd()
    }

    private func startSendTimer() {
        let timer = DispatchSource.makeTimerSource(queue: .main)
        let interval = DispatchTimeInterval.milliseconds(max(1, options.intervalMs))
        timer.schedule(deadline: .now(), repeating: interval)
        timer.setEventHandler { [weak self] in
            self?.sendNextPacket()
        }
        timer.resume()
        sendTimer = timer
    }

    private func scheduleWriteWindowEnd() {
        DispatchQueue.main.asyncAfter(deadline: .now() + options.seconds) { [weak self] in
            guard let self else { return }
            self.sendTimer?.cancel()
            self.sendTimer = nil
            print("Write window of \(Int(self.options.seconds))s elapsed.")
            self.stopRunLoopSoon()
        }
    }

    private func sendNextPacket() {
        guard let peripheral, let characteristic else { return }
        let packet = FeedbackCrypto.buildPacket(counter: counter, dx: options.dx, dy: options.dy)
        peripheral.writeValue(packet, for: characteristic, type: .withoutResponse)
        counter = counter &+ 1
        packetsSent += 1
        if packetsSent == 1 || packetsSent % 200 == 0 {
            print("Wrote \(packetsSent) packets (counter=\(counter)).")
        }
    }
}
