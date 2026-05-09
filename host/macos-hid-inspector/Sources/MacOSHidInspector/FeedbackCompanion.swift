import BluetrackHostKit
import CoreBluetooth
import Foundation

struct FeedbackOptions {
    var dx: Float = 1.25
    var dy: Float = -0.75
    var intervalMs: Int = 5
    var scanTimeout: Double = 10.0
    var seconds: Double = 15.0
    /// Pairing pin shown on the Bluetrack status row. Must satisfy
    /// `FeedbackCrypto.normalizedPinBytes`. Required.
    var pin: String = ""
    /// Long-term Ed25519 identity used to sign the BLE handshake so the
    /// peripheral can pin the host (TOFU). Must be set before `run()`.
    var hostIdentity: HostIdentity?
}

final class FeedbackCompanion: NSObject {
    private let options: FeedbackOptions
    private let serviceUUID = CBUUID(string: FeedbackCrypto.serviceUUIDString)
    private let feedbackCharUUID = CBUUID(string: FeedbackCrypto.feedbackCharacteristicUUIDString)
    private let handshakeCharUUID = CBUUID(string: FeedbackCrypto.handshakeCharacteristicUUIDString)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var feedbackCharacteristic: CBCharacteristic?
    private var handshakeCharacteristic: CBCharacteristic?
    private var session = FeedbackSession()
    private var counter: UInt32 = 0
    private var packetsSent = 0
    private var sendTimer: DispatchSourceTimer?
    private var scanDeadline: DispatchWorkItem?
    private var scanStartedAt: Date?
    private var connectedAt: Date?
    private var charReadyAt: Date?
    private var sessionReadyAt: Date?
    private var scanFailed = false
    private var firstDiscoveryFired = false
    private var handshakePubKeyWritten = false
    private var handshakeFailed = false
    private var handshakeFailureReason: String?

    /// Fired exactly once when scanning resolves, either by discovering the
    /// first peripheral (parameter is its advertised name, possibly nil) or
    /// by hitting the scan deadline (parameter is nil). Used by the
    /// `companion` runner to cross-feed the BLE name into the HID filter
    /// before HID enumeration runs.
    var onFirstScanResult: ((String?) -> Void)?

    /// Advertised name of the peripheral discovered during scanning, or nil
    /// if no peripheral has been seen yet.
    var discoveredPeripheralName: String? { peripheral?.name }

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
        if let sessionReadyAt {
            writeDuration = Date().timeIntervalSince(sessionReadyAt)
        } else if let charReadyAt {
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
        if feedbackCharacteristic == nil {
            print("Peripheral connected but characteristic \(FeedbackCrypto.feedbackCharacteristicUUIDString) was not discovered.")
            return 5
        }
        if handshakeCharacteristic == nil {
            print("Peripheral connected but handshake characteristic \(FeedbackCrypto.handshakeCharacteristicUUIDString) was not discovered. The phone may be running an older Bluetrack build.")
            return 5
        }
        if handshakeFailed {
            print("Handshake failed: \(handshakeFailureReason ?? "unknown error").")
            return 5
        }
        if !session.isReady {
            print("Handshake never completed within the run window. No encrypted frames were written.")
            return 5
        }
        print("Wrote \(packetsSent) feedback packets.")
        if packetsSent == 0 {
            print("No packets were written even though the session was ready. The send timer never fired or seconds was too short.")
            return 6
        }
        if let sessionReadyAt {
            let writeWindow = Date().timeIntervalSince(sessionReadyAt)
            print("Write window: \(String(format: "%.1f", writeWindow))s. If the Android timeline shows feedback packets, the BLE path is healthy.")
        }
        return 0
    }

    private func stopRunLoopSoon() {
        DispatchQueue.main.async { CFRunLoopStop(CFRunLoopGetMain()) }
    }

    private func fireFirstScanResult(name: String?) {
        guard !firstDiscoveryFired else { return }
        firstDiscoveryFired = true
        onFirstScanResult?(name)
    }

    private func failHandshake(_ reason: String) {
        handshakeFailed = true
        handshakeFailureReason = reason
        print("Handshake failed: \(reason).")
        stopRunLoopSoon()
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
            // Forward scan timeout to the cross-feed observer too, so the
            // companion runner doesn't wait beyond the BLE deadline if the
            // peripheral never appears.
            let crossFeedDeadline = DispatchWorkItem { [weak self] in
                self?.fireFirstScanResult(name: nil)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + options.scanTimeout, execute: crossFeedDeadline)
        case .poweredOff:
            print("Bluetooth is off. Enable it and rerun.")
            fireFirstScanResult(name: nil)
            stopRunLoopSoon()
        case .unauthorized:
            print("Bluetooth permission denied for this process. Grant Terminal access in System Settings → Privacy & Security → Bluetooth.")
            fireFirstScanResult(name: nil)
            stopRunLoopSoon()
        case .unsupported:
            print("This Mac does not support Bluetooth LE.")
            fireFirstScanResult(name: nil)
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
        fireFirstScanResult(name: peripheral.name)
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
        peripheral.discoverCharacteristics([feedbackCharUUID, handshakeCharUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error {
            print("Characteristic discovery failed: \(error.localizedDescription).")
            stopRunLoopSoon()
            return
        }
        feedbackCharacteristic = service.characteristics?.first { $0.uuid == feedbackCharUUID }
        handshakeCharacteristic = service.characteristics?.first { $0.uuid == handshakeCharUUID }
        guard let handshake = handshakeCharacteristic else {
            print("Service did not expose handshake characteristic \(FeedbackCrypto.handshakeCharacteristicUUIDString).")
            stopRunLoopSoon()
            return
        }
        guard feedbackCharacteristic != nil else {
            print("Service did not expose feedback characteristic \(FeedbackCrypto.feedbackCharacteristicUUIDString).")
            stopRunLoopSoon()
            return
        }
        charReadyAt = Date()
        guard let identity = options.hostIdentity else {
            failHandshake("host identity is unset (compile-time wiring bug)")
            return
        }
        let payloadData: Data
        do {
            let payload = try FeedbackHandshakePayload.build(
                ephemeralPublicKey: session.publicKey,
                hostIdentity: identity
            )
            payloadData = payload.encoded()
        } catch {
            failHandshake("could not build handshake: \(error)")
            return
        }
        print("Characteristics ready. Starting handshake (\(payloadData.count) bytes: eph X25519 + Ed25519 id \(identity.fingerprint()) + sig)...")
        // 1) Write the signed handshake payload first; the peripheral
        //    verifies sig + TOFU-checks identity in
        //    onCharacteristicWriteRequest before responding.
        peripheral.writeValue(payloadData, for: handshake, type: .withResponse)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == handshakeCharUUID else { return }
        if let error {
            failHandshake("write failed: \(error.localizedDescription)")
            return
        }
        handshakePubKeyWritten = true
        // 2) Read the peripheral's pubkey. The read landing in
        //    didUpdateValueFor below will derive our session and start writes.
        peripheral.readValue(for: characteristic)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == handshakeCharUUID else { return }
        if let error {
            failHandshake("read failed: \(error.localizedDescription)")
            return
        }
        guard let value = characteristic.value, value.count == FeedbackCrypto.publicKeySize else {
            failHandshake("peripheral returned \(characteristic.value?.count ?? 0)-byte handshake (expected \(FeedbackCrypto.publicKeySize))")
            return
        }
        do {
            try session.deriveSession(peerPublicKey: value, pin: options.pin)
        } catch {
            failHandshake("ECDH derivation failed: \(error)")
            return
        }
        sessionReadyAt = Date()
        let handshakeMs: Int
        if let charReadyAt {
            handshakeMs = Int(Date().timeIntervalSince(charReadyAt) * 1000)
        } else {
            handshakeMs = 0
        }
        print("Handshake complete in \(handshakeMs)ms. Writing every \(options.intervalMs)ms.")
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
        guard let peripheral, let characteristic = feedbackCharacteristic else { return }
        let packet: Data
        do {
            packet = try session.buildPacket(counter: counter, dx: options.dx, dy: options.dy)
        } catch {
            failHandshake("buildPacket: \(error)")
            return
        }
        peripheral.writeValue(packet, for: characteristic, type: .withoutResponse)
        counter = counter &+ 1
        packetsSent += 1
        if packetsSent == 1 || packetsSent % 200 == 0 {
            print("Wrote \(packetsSent) packets (counter=\(counter)).")
        }
    }
}
