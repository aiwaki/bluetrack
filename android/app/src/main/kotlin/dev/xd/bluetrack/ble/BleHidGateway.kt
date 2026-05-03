package dev.xd.bluetrack.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import dev.xd.bluetrack.engine.GamepadReportFormat
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class GatewayStatus(
    val hid: String = "Idle",
    val feedback: String = "Idle",
    val pairing: String = "Not discoverable",
    val host: String? = null,
    val error: String? = null,
    val compatibility: CompatibilitySnapshot = CompatibilitySnapshot(),
    val events: List<GatewayEvent> = emptyList(),
    val reportsSent: Int = 0,
    val feedbackPackets: Int = 0,
    val rejectedFeedbackPackets: Int = 0,
    val lastInputSource: String? = null,
    val lastInputAtMs: Long? = null,
    val lastReportAtMs: Long? = null,
    val lastFeedbackAtMs: Long? = null,
)

data class CompatibilitySnapshot(
    val bluetoothAvailable: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val bleAdvertiserAvailable: Boolean? = null,
    val multipleAdvertisementSupported: Boolean? = null,
    val hidProfile: String = "Unknown",
    val scanMode: String = "Unknown",
    val bondedDevices: List<String> = emptyList(),
)

data class GatewayEvent(
    val timestampMs: Long,
    val source: String,
    val message: String,
)

@SuppressLint("MissingPermission")
class BleHidGateway(private val context: Context, private val engine: TranslationEngine) {
    private companion object {
        const val TAG = "Bluetrack"
        const val MOUSE_REPORT_ID = 1
        const val GAMEPAD_REPORT_ID = 2
        const val MAX_EVENTS = 24
        const val REPORT_STATUS_INTERVAL_MS = 250L
        const val REPORT_EVENT_INTERVAL = 50
        const val GAMEPAD_WAKE_HOLD_MS = 120L
        const val GAMEPAD_WAKE_REST_MS = 80L
        const val GAMEPAD_WAKE_REPEATS = 3
        const val GAMEPAD_WAKE_MIN_INTERVAL_MS = 1500L
        val COMPOSITE_HID_SUBCLASS: Byte = (
            BluetoothHidDevice.SUBCLASS1_COMBO.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()
        ).toByte()
        val FEEDBACK_SERVICE_UUID: UUID = UUID.fromString("0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263")
        val FEEDBACK_CHARACTERISTIC_UUID: UUID = UUID.fromString("4846ff87-f2d4-4df2-9500-9bf8ed23f9e6")
    }

    private val decryptor = PayloadDecryptor()
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var pendingMode = HidMode.MOUSE
    private var registeredMode: HidMode? = null
    private var registrationInFlight = false
    private var hasRegisteredOnce = false
    private var gamepadWakeArmed = false
    private var gamepadWakeInFlight = false
    private var profileProxyRequested = false
    private var reportsSent = 0
    private var feedbackPackets = 0
    private var rejectedFeedbackPackets = 0
    private var lastNoHostReportWarningMs = 0L
    private var lastAutoConnectAttemptMs = 0L
    private var lastNoComputerHostWarningMs = 0L
    private var lastReportStatusAtMs = 0L
    private var lastGamepadWakeAtMs = 0L
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            updateStatus(
                hid = "HID proxy ready",
                compatibility = snapshotCompatibility(),
                error = null,
                eventSource = "HID",
                eventMessage = "HID Device profile proxy connected.",
            )
            register(pendingMode)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            host = null
            registeredMode = null
            registrationInFlight = false
            profileProxyRequested = false
            updateStatus(
                hid = "HID proxy disconnected",
                compatibility = snapshotCompatibility(),
                host = null,
                eventSource = "HID",
                eventMessage = "HID Device profile proxy disconnected.",
            )
        }
    }

    private val mouseDesc = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), MOUSE_REPORT_ID.toByte(),
        0x09, 0x01, 0xA1.toByte(), 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00,
        0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02, 0x95.toByte(),
        0x01, 0x75, 0x05, 0x81.toByte(), 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09,
        0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte(),
    )
    private val gamepadDesc = byteArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), GAMEPAD_REPORT_ID.toByte(),
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x10, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00,
        0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95.toByte(), 0x01,
        0x81.toByte(), 0x42, 0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01,
        0x81.toByte(), 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x35, 0x81.toByte(), 0x45, 0x7F,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0xC0.toByte(),
    )
    private val compositeDesc = mouseDesc + gamepadDesc

    @Synchronized
    fun initialize(announceCompatibility: Boolean = true) {
        refreshCompatibility(announce = announceCompatibility)
        val bluetoothAdapter = adapter ?: run {
            updateStatus(
                hid = "Bluetooth unavailable",
                feedback = "Bluetooth unavailable",
                compatibility = snapshotCompatibility(),
                eventSource = "Bluetooth",
                eventMessage = "No Bluetooth adapter was found.",
            )
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            updateStatus(
                hid = "Bluetooth off",
                feedback = "Bluetooth off",
                compatibility = snapshotCompatibility(),
                host = null,
                eventSource = "Bluetooth",
                eventMessage = "Bluetooth is disabled.",
            )
            return
        }

        try {
            if (!profileProxyRequested) {
                profileProxyRequested = bluetoothAdapter.getProfileProxy(
                    context,
                    profileListener,
                    BluetoothProfile.HID_DEVICE,
                )
                if (!profileProxyRequested) {
                    updateStatus(
                        hid = "HID profile unavailable",
                        compatibility = snapshotCompatibility(),
                        eventSource = "HID",
                        eventMessage = "Android rejected the HID Device profile proxy request.",
                    )
                } else {
                    updateStatus(
                        hid = "Waiting for HID proxy",
                        compatibility = snapshotCompatibility(),
                        eventSource = "HID",
                        eventMessage = "Requested HID Device profile proxy.",
                    )
                }
            }
            startGatt()
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    @Synchronized
    fun register(mode: HidMode) {
        pendingMode = mode
        val device = hid ?: run {
            updateStatus(hid = "Waiting for HID proxy")
            return
        }

        try {
            if (registeredMode != null) {
                val previousMode = registeredMode
                registeredMode = mode
                if (previousMode != HidMode.GAMEPAD && mode == HidMode.GAMEPAD) {
                    gamepadWakeArmed = true
                }
                sendNeutralReports()
                updateStatus(
                    hid = if (host == null) "HID ready (${mode.name})" else "Connected (${mode.name})",
                    compatibility = snapshotCompatibility(),
                    eventSource = if (previousMode == mode) null else "HID",
                    eventMessage = if (previousMode == mode) {
                        null
                    } else {
                        "Switched active mode from ${previousMode?.name} to ${mode.name} without reconnecting."
                    },
                )
                if (previousMode != HidMode.GAMEPAD && mode == HidMode.GAMEPAD) {
                    sendGamepadWakePulse("mode switch")
                    gamepadWakeArmed = true
                }
                maybeAutoConnectHost("mode check")
                return
            }

            if (registrationInFlight) {
                updateStatus(
                    hid = "Registering HID (${mode.name})",
                    compatibility = snapshotCompatibility(),
                )
                return
            }

            val q = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                800,
                9,
                0,
                11250,
                11250,
            )
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Bluetrack Pro Engine",
                "Input Translation",
                "Bluetrack",
                COMPOSITE_HID_SUBCLASS,
                compositeDesc,
            )
            val modeAtRegistration = mode
            val accepted = device.registerApp(sdp, null, q, ioExecutor, object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    registrationInFlight = false
                    val hadRegisteredBefore = hasRegisteredOnce
                    if (registered) {
                        hasRegisteredOnce = true
                        if (modeAtRegistration == HidMode.GAMEPAD) gamepadWakeArmed = true
                    }
                    registeredMode = if (registered) modeAtRegistration else null
                    updateStatus(
                        hid = if (registered) "HID ready (${modeAtRegistration.name})" else "HID app inactive",
                        compatibility = snapshotCompatibility(),
                        error = when {
                            registered -> null
                            hadRegisteredBefore -> "HID registration stopped; Bluetrack will retry automatically."
                            else -> "Android rejected the HID app registration."
                        },
                        eventSource = "HID",
                        eventMessage = if (registered) {
                            "Composite HID app registered with mouse and gamepad reports; active mode ${modeAtRegistration.name}."
                        } else if (hadRegisteredBefore) {
                            "HID app registration became inactive; maintenance will retry."
                        } else {
                            "HID app registration failed."
                        },
                    )
                    if (registered) maybeAutoConnectHost("HID registration")
                }

                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    host = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                    if (state == BluetoothProfile.STATE_CONNECTED && registeredMode == HidMode.GAMEPAD) {
                        gamepadWakeArmed = true
                    }
                    updateStatus(
                        hid = when (state) {
                            BluetoothProfile.STATE_CONNECTING -> "Connecting"
                            BluetoothProfile.STATE_CONNECTED -> "Connected (${registeredMode?.name ?: modeAtRegistration.name})"
                            BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
                            else -> "HID ready (${registeredMode?.name ?: modeAtRegistration.name})"
                        },
                        compatibility = snapshotCompatibility(),
                        pairing = if (state == BluetoothProfile.STATE_CONNECTED) {
                            "Paired and HID connected"
                        } else {
                            pairingLabel(snapshotCompatibility())
                        },
                        host = if (state == BluetoothProfile.STATE_CONNECTED) device.safeName() else null,
                        error = if (state == BluetoothProfile.STATE_CONNECTED) null else _status.value.error,
                        eventSource = "HID",
                        eventMessage = "Host ${device.safeName()} is ${state.connectionStateLabel()}.",
                    )
                    if (state == BluetoothProfile.STATE_CONNECTED && registeredMode == HidMode.GAMEPAD) {
                        sendGamepadWakePulse("host connected")
                        gamepadWakeArmed = true
                    }
                }
            })
            registrationInFlight = accepted
            if (!accepted) {
                updateStatus(
                    hid = "HID registration rejected",
                    compatibility = snapshotCompatibility(),
                    error = "HID registration request was rejected; Bluetrack will retry automatically.",
                    eventSource = "HID",
                    eventMessage = "BluetoothHidDevice.registerApp returned false.",
                )
            }
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    @Synchronized
    fun connectBondedHost() {
        val device = hid ?: run {
            updateStatus(
                hid = "Waiting for HID proxy",
                eventSource = "HID",
                eventMessage = "Cannot connect host until HID proxy is ready.",
            )
            return
        }
        val mode = registeredMode ?: run {
            updateStatus(
                hid = "Waiting for HID registration",
                eventSource = "HID",
                eventMessage = "Cannot connect host until HID app registration finishes.",
            )
            return
        }
        val bluetoothAdapter = adapter ?: run {
            updateStatus(
                hid = "Bluetooth unavailable",
                eventSource = "Bluetooth",
                eventMessage = "Cannot connect host without a Bluetooth adapter.",
            )
            return
        }

        try {
            val bondedDevices = bluetoothAdapter.bondedDevices
            val candidate = bondedDevices.bestHidHost()

            if (candidate == null) {
                val ignoredDevices = bondedDevices
                    .map { it.safeName() }
                    .sorted()
                updateStatus(
                    pairing = if (ignoredDevices.isEmpty()) "No bonded host" else "No computer HID host",
                    compatibility = snapshotCompatibility(),
                    error = if (ignoredDevices.isEmpty()) {
                        "Pair the PC first; Bluetrack will connect the HID host automatically."
                    } else {
                        "Ignoring bonded devices that do not look like computer HID hosts."
                    },
                    eventSource = "HID",
                    eventMessage = if (ignoredDevices.isEmpty()) {
                        "No bonded Bluetooth devices are available for HID connect."
                    } else {
                        "Ignored non-host bonded devices: ${ignoredDevices.joinToString()}."
                    },
                )
                return
            }

            if (host?.address == candidate.address) {
                updateStatus(
                    hid = "Connected",
                    pairing = "Paired and HID connected",
                    compatibility = snapshotCompatibility(),
                    host = candidate.safeName(),
                    error = null,
                    eventSource = "HID",
                    eventMessage = "Already connected to ${candidate.safeName()} as $mode.",
                )
                return
            }

            val accepted = device.connect(candidate)
            lastAutoConnectAttemptMs = SystemClock.elapsedRealtime()
            updateStatus(
                hid = if (accepted) "Connecting to host" else "Host connect rejected",
                pairing = if (accepted) "Bonded, connecting HID" else pairingLabel(snapshotCompatibility()),
                compatibility = snapshotCompatibility(),
                error = if (accepted) null else "Android rejected the HID connect request.",
                eventSource = "HID",
                eventMessage = if (accepted) {
                    "Requested HID connection to ${candidate.safeName()} as $mode."
                } else {
                    "BluetoothHidDevice.connect returned false for ${candidate.safeName()}."
                },
            )
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    private fun maybeAutoConnectHost(reason: String) {
        val snapshot = snapshotCompatibility()
        val now = SystemClock.elapsedRealtime()
        if (hid == null || registeredMode == null || host != null || snapshot.bondedDevices.isEmpty()) return
        if (now - lastAutoConnectAttemptMs < 4000L) return
        val bluetoothAdapter = adapter ?: return
        val candidate = try {
            bluetoothAdapter.bondedDevices.bestHidHost()
        } catch (_: SecurityException) {
            reportPermissionMissing()
            return
        }
        if (candidate == null) {
            updateStatus(
                pairing = "No computer HID host",
                compatibility = snapshot,
                error = "Bonded devices exist, but none look like a computer HID host.",
                eventSource = if (now - lastNoComputerHostWarningMs > 30000L) "HID" else null,
                eventMessage = if (now - lastNoComputerHostWarningMs > 30000L) {
                    lastNoComputerHostWarningMs = now
                    "Skipped auto-connect because no bonded device looks like a computer HID host."
                } else {
                    null
                },
            )
            return
        }

        updateStatus(
            pairing = "Bonded, auto-connecting HID",
            compatibility = snapshot,
            eventSource = "HID",
            eventMessage = "Auto-connecting bonded HID host ${candidate.safeName()} after $reason.",
        )
        connectBondedHost()
    }

    private fun sendNeutralReports() {
        val target = host ?: return
        val device = hid ?: return
        try {
            device.sendReport(target, MOUSE_REPORT_ID, byteArrayOf(0, 0, 0, 0))
            device.sendReport(target, GAMEPAD_REPORT_ID, GamepadReportFormat.neutralReport())
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    @Synchronized
    private fun sendGamepadWakePulse(reason: String) {
        val target = host ?: return
        val device = hid ?: return
        val now = SystemClock.elapsedRealtime()
        if (gamepadWakeInFlight || now - lastGamepadWakeAtMs < GAMEPAD_WAKE_MIN_INTERVAL_MS) return

        gamepadWakeInFlight = true
        gamepadWakeArmed = false
        lastGamepadWakeAtMs = now
        ioExecutor.execute {
            var sentReports = 0
            try {
                repeat(GAMEPAD_WAKE_REPEATS) { repeatIndex ->
                    if (device.sendReport(target, GAMEPAD_REPORT_ID, GamepadReportFormat.buttonAWakeReport())) {
                        sentReports += 1
                    }
                    Thread.sleep(GAMEPAD_WAKE_HOLD_MS)
                    if (device.sendReport(target, GAMEPAD_REPORT_ID, GamepadReportFormat.neutralReport())) {
                        sentReports += 1
                    }
                    if (repeatIndex < GAMEPAD_WAKE_REPEATS - 1) {
                        Thread.sleep(GAMEPAD_WAKE_REST_MS)
                    }
                }
                synchronized(this@BleHidGateway) {
                    reportsSent += sentReports
                    updateStatus(
                        reportsSent = reportsSent,
                        lastReportAtMs = SystemClock.elapsedRealtime(),
                        eventSource = "Gamepad",
                        eventMessage = "Sent a visible gamepad wake train after $reason.",
                    )
                }
            } catch (_: SecurityException) {
                reportPermissionMissing()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(this@BleHidGateway) {
                    gamepadWakeInFlight = false
                }
            }
        }
    }

    fun recordInput(source: String) {
        val previousSource = _status.value.lastInputSource
        updateStatus(
            lastInputSource = source,
            lastInputAtMs = SystemClock.elapsedRealtime(),
            eventSource = if (previousSource == source) null else "Input",
            eventMessage = if (previousSource == source) null else "Input source changed to $source.",
        )
    }

    fun send(mode: HidMode, report: ByteArray) {
        val target: BluetoothDevice
        val device: BluetoothHidDevice?
        try {
            synchronized(this) {
                val connectedHost = host
                if (connectedHost == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNoHostReportWarningMs > 2000L) {
                        lastNoHostReportWarningMs = now
                        updateStatus(
                            error = "No HID host connected; pair the host and Bluetrack will connect automatically.",
                            eventSource = "HID",
                            eventMessage = "Input was received, but no HID host is connected.",
                        )
                    }
                    return
                }
                target = connectedHost
                device = hid
                if (mode == HidMode.GAMEPAD && gamepadWakeArmed) {
                    sendGamepadWakePulse("first gamepad input")
                }
            }
            val sent = device?.sendReport(
                target,
                if (mode == HidMode.MOUSE) MOUSE_REPORT_ID else GAMEPAD_REPORT_ID,
                report,
            ) == true
            synchronized(this) {
                if (sent) {
                    reportsSent += 1
                    publishReportStatusIfDue()
                } else {
                    updateStatus(
                        error = "Android rejected a HID report send.",
                        eventSource = "HID",
                        eventMessage = "sendReport returned false.",
                    )
                }
            }
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    private fun publishReportStatusIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (reportsSent != 1 && now - lastReportStatusAtMs < REPORT_STATUS_INTERVAL_MS) return

        lastReportStatusAtMs = now
        val shouldLogEvent = reportsSent == 1 || reportsSent % REPORT_EVENT_INTERVAL == 0
        updateStatus(
            reportsSent = reportsSent,
            lastReportAtMs = now,
            eventSource = if (shouldLogEvent) "HID" else null,
            eventMessage = if (shouldLogEvent) {
                "Sent $reportsSent HID reports."
            } else {
                null
            },
        )
    }

    @Synchronized
    fun shutdown() {
        updateStatus(eventSource = "Lifecycle", eventMessage = "Shutting down Bluetooth gateway.")
        stopFeedbackAdvertising()
        gattServer?.close()
        gattServer = null
        try {
            hid?.unregisterApp()
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: SecurityException) {
            // Activity is already shutting down; state no longer matters.
        }
        hid = null
        host = null
        registeredMode = null
        registrationInFlight = false
        profileProxyRequested = false
        ioExecutor.shutdownNow()
    }

    @Synchronized
    fun maintainRegistration(mode: HidMode = pendingMode) {
        initialize(announceCompatibility = false)
        register(mode)
        refreshCompatibility(announce = false)
    }

    @Synchronized
    fun refreshCompatibility(announce: Boolean = true) {
        val snapshot = snapshotCompatibility()
        updateStatus(
            pairing = pairingLabel(snapshot),
            compatibility = snapshot,
            eventSource = if (announce) "Compatibility" else null,
            eventMessage = if (announce) "Compatibility snapshot refreshed." else null,
        )
        maybeAutoConnectHost("compatibility refresh")
    }

    fun reportPermissionMissing() {
        updateStatus(
            hid = "Bluetooth permission missing",
            feedback = "Bluetooth permission missing",
            compatibility = snapshotCompatibility(),
            error = "Grant nearby-devices Bluetooth permissions and reopen the app.",
            eventSource = "Permission",
            eventMessage = "Bluetooth permission is missing.",
        )
    }

    fun reportBluetoothEnableRequested() {
        updateStatus(
            hid = "Bluetooth enable requested",
            feedback = "Bluetooth enable requested",
            compatibility = snapshotCompatibility(),
            eventSource = "Bluetooth",
            eventMessage = "Requested Android Bluetooth enable flow.",
        )
    }

    fun reportBluetoothDisabled() {
        updateStatus(
            hid = "Bluetooth off",
            feedback = "Bluetooth off",
            compatibility = snapshotCompatibility(),
            host = null,
            eventSource = "Bluetooth",
            eventMessage = "Bluetooth remains disabled.",
        )
    }

    fun reportDiscoverable(seconds: Int) {
        val snapshot = snapshotCompatibility()
        updateStatus(
            pairing = "Discoverable for ${seconds}s",
            compatibility = snapshot,
            error = null,
            eventSource = "Pairing",
            eventMessage = "Android reports discoverability for ${seconds}s.",
        )
        maybeAutoConnectHost("discoverability result")
    }

    fun reportDiscoverabilityRequested(auto: Boolean) {
        updateStatus(
            pairing = "Opening pairing window",
            compatibility = snapshotCompatibility(),
            error = null,
            eventSource = "Pairing",
            eventMessage = if (auto) {
                "Autopilot requested Android discoverability."
            } else {
                "Requested Android discoverability."
            },
        )
    }

    fun reportDiscoverableRejected() {
        updateStatus(
            pairing = "Discoverability cancelled",
            compatibility = snapshotCompatibility(),
            eventSource = "Pairing",
            eventMessage = "Discoverability prompt was cancelled.",
        )
    }

    private fun startGatt() {
        if (gattServer != null) return
        updateStatus(
            feedback = "Opening feedback GATT",
            compatibility = snapshotCompatibility(),
            eventSource = "Feedback",
            eventMessage = "Opening BLE feedback GATT server.",
        )
        gattServer = try {
            btManager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                    if (service.uuid != FEEDBACK_SERVICE_UUID) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        updateStatus(
                            feedback = "Feedback service ready",
                            compatibility = snapshotCompatibility(),
                            eventSource = "Feedback",
                            eventMessage = "Feedback GATT service was added.",
                        )
                        startFeedbackAdvertising()
                    } else {
                        updateStatus(
                            feedback = "Feedback service failed",
                            compatibility = snapshotCompatibility(),
                            error = "Android rejected the BLE feedback GATT service: $status.",
                            eventSource = "Feedback",
                            eventMessage = "Feedback GATT service add failed with status $status.",
                        )
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray,
                ) {
                    val accepted = !preparedWrite && offset == 0 && decryptor.decryptPayloadTo(value) { correctionX, correctionY ->
                        engine.updateCorrection(correctionX, correctionY)
                    }
                    if (accepted) {
                        feedbackPackets += 1
                        updateStatus(
                            feedback = "Feedback packets: $feedbackPackets",
                            feedbackPackets = feedbackPackets,
                            lastFeedbackAtMs = SystemClock.elapsedRealtime(),
                            eventSource = if (feedbackPackets == 1 || feedbackPackets % 100 == 0) "Feedback" else null,
                            eventMessage = if (feedbackPackets == 1 || feedbackPackets % 100 == 0) {
                                "Received $feedbackPackets feedback packets."
                            } else {
                                null
                            },
                        )
                    } else {
                        rejectedFeedbackPackets += 1
                        updateStatus(
                            rejectedFeedbackPackets = rejectedFeedbackPackets,
                            error = "Rejected invalid BLE feedback packet.",
                            eventSource = "Feedback",
                            eventMessage = "Rejected feedback packet #$rejectedFeedbackPackets.",
                        )
                    }
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                                offset,
                                null,
                            )
                        } catch (_: SecurityException) {
                            reportPermissionMissing()
                        }
                    }
                }
            })
        } catch (_: SecurityException) {
            reportPermissionMissing()
            null
        }
        val server = gattServer ?: run {
            updateStatus(
                feedback = "Feedback GATT unavailable",
                compatibility = snapshotCompatibility(),
                eventSource = "Feedback",
                eventMessage = "openGattServer returned null.",
            )
            return
        }
        val service = BluetoothGattService(
            FEEDBACK_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FEEDBACK_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        if (!server.addService(service)) {
            updateStatus(
                feedback = "Feedback service rejected",
                compatibility = snapshotCompatibility(),
                eventSource = "Feedback",
                eventMessage = "BluetoothGattServer.addService returned false.",
            )
        }
    }

    private fun startFeedbackAdvertising() {
        if (advertiseCallback != null) return
        val bluetoothAdapter = adapter ?: return
        val leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            updateStatus(
                feedback = "BLE advertising unavailable",
                compatibility = snapshotCompatibility(),
                eventSource = "Feedback",
                eventMessage = "BluetoothLeAdvertiser is unavailable.",
            )
            return
        }
        advertiser = leAdvertiser
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                updateStatus(
                    feedback = "Advertising feedback service",
                    compatibility = snapshotCompatibility(),
                    eventSource = "Feedback",
                    eventMessage = "BLE feedback advertising started.",
                )
            }

            override fun onStartFailure(errorCode: Int) {
                advertiseCallback = null
                updateStatus(
                    feedback = "Feedback advertising failed",
                    compatibility = snapshotCompatibility(),
                    error = "BLE advertising failed with Android error code $errorCode.",
                    eventSource = "Feedback",
                    eventMessage = "BLE feedback advertising failed with code $errorCode.",
                )
            }
        }
        advertiseCallback = callback
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(FEEDBACK_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        try {
            updateStatus(
                feedback = "Starting feedback advertising",
                compatibility = snapshotCompatibility(),
                eventSource = "Feedback",
                eventMessage = "Starting BLE feedback advertising.",
            )
            leAdvertiser.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {
            advertiseCallback = null
            reportPermissionMissing()
        }
    }

    private fun stopFeedbackAdvertising() {
        val callback = advertiseCallback ?: return
        try {
            advertiser?.stopAdvertising(callback)
            updateStatus(
                feedback = "Feedback advertising stopped",
                compatibility = snapshotCompatibility(),
                eventSource = "Feedback",
                eventMessage = "Stopped BLE feedback advertising.",
            )
        } catch (_: SecurityException) {
            reportPermissionMissing()
        } finally {
            advertiseCallback = null
            advertiser = null
        }
    }

    private fun snapshotCompatibility(): CompatibilitySnapshot {
        val bluetoothAdapter = adapter ?: return CompatibilitySnapshot(
            bluetoothAvailable = false,
            bluetoothEnabled = false,
            hidProfile = "No adapter",
            scanMode = "Unavailable",
        )

        return try {
            val enabled = bluetoothAdapter.isEnabled
            CompatibilitySnapshot(
                bluetoothAvailable = true,
                bluetoothEnabled = enabled,
                bleAdvertiserAvailable = if (enabled) bluetoothAdapter.bluetoothLeAdvertiser != null else null,
                multipleAdvertisementSupported = if (enabled) bluetoothAdapter.isMultipleAdvertisementSupported else null,
                hidProfile = when {
                    hid != null && registeredMode != null -> "Composite active ${registeredMode?.name}"
                    hid != null -> "Proxy ready"
                    profileProxyRequested -> "Proxy requested"
                    enabled -> "Not requested"
                    else -> "Bluetooth off"
                },
                scanMode = if (enabled) bluetoothAdapter.scanMode.scanModeLabel() else "Bluetooth off",
                bondedDevices = if (enabled) {
                    bluetoothAdapter.bondedDevices
                        .map { it.safeName() }
                        .sorted()
                } else {
                    emptyList()
                },
            )
        } catch (_: SecurityException) {
            CompatibilitySnapshot(
                bluetoothAvailable = true,
                bluetoothEnabled = false,
                hidProfile = "Permission missing",
                scanMode = "Permission missing",
            )
        }
    }

    private fun pairingLabel(snapshot: CompatibilitySnapshot): String = when {
        host != null -> "Paired and HID connected"
        snapshot.bondedDevices.isNotEmpty() -> "Bonded, HID disconnected"
        snapshot.scanMode == "Connectable and discoverable" -> "Discoverable"
        else -> _status.value.pairing
    }

    private fun BluetoothDevice.safeName(): String =
        try {
            name ?: address ?: "Host"
        } catch (_: SecurityException) {
            "Host"
        }

    private fun Set<BluetoothDevice>.bestHidHost(): BluetoothDevice? =
        mapNotNull { device ->
            val candidate = HidHostCandidate(
                name = device.safeName(),
                majorDeviceClass = device.bluetoothClass?.majorDeviceClass,
            )
            candidate.hidHostRank()?.let { rank -> rank to device }
        }
            .sortedWith(
                compareByDescending<Pair<Int, BluetoothDevice>> { it.first }
                    .thenBy { it.second.safeName() }
            )
            .firstOrNull()
            ?.second

    private fun Int.connectionStateLabel(): String = when (this) {
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        else -> "state $this"
    }

    private fun Int.scanModeLabel(): String = when (this) {
        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "Connectable and discoverable"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "Connectable"
        BluetoothAdapter.SCAN_MODE_NONE -> "None"
        else -> "Unknown ($this)"
    }

    private fun updateStatus(
        hid: String? = null,
        feedback: String? = null,
        pairing: String? = null,
        compatibility: CompatibilitySnapshot? = null,
        host: String? = _status.value.host,
        error: String? = _status.value.error,
        reportsSent: Int? = null,
        feedbackPackets: Int? = null,
        rejectedFeedbackPackets: Int? = null,
        lastInputSource: String? = _status.value.lastInputSource,
        lastInputAtMs: Long? = _status.value.lastInputAtMs,
        lastReportAtMs: Long? = _status.value.lastReportAtMs,
        lastFeedbackAtMs: Long? = _status.value.lastFeedbackAtMs,
        eventSource: String? = null,
        eventMessage: String? = null,
    ) {
        val current = _status.value
        val event = if (!eventSource.isNullOrBlank() && !eventMessage.isNullOrBlank()) {
            val next = GatewayEvent(SystemClock.elapsedRealtime(), eventSource, eventMessage)
            Log.i(TAG, "${next.source}: ${next.message}")
            next
        } else {
            null
        }
        _status.value = current.copy(
            hid = hid ?: current.hid,
            feedback = feedback ?: current.feedback,
            pairing = pairing ?: current.pairing,
            compatibility = compatibility ?: current.compatibility,
            host = host,
            error = error,
            reportsSent = reportsSent ?: current.reportsSent,
            feedbackPackets = feedbackPackets ?: current.feedbackPackets,
            rejectedFeedbackPackets = rejectedFeedbackPackets ?: current.rejectedFeedbackPackets,
            lastInputSource = lastInputSource,
            lastInputAtMs = lastInputAtMs,
            lastReportAtMs = lastReportAtMs,
            lastFeedbackAtMs = lastFeedbackAtMs,
            events = if (event == null) current.events else (listOf(event) + current.events).take(MAX_EVENTS),
        )
    }
}
