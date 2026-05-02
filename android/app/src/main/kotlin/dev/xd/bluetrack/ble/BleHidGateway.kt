package dev.xd.bluetrack.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
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
)

@SuppressLint("MissingPermission")
class BleHidGateway(private val context: Context, private val engine: TranslationEngine) {
    private companion object {
        const val MOUSE_REPORT_ID = 1
        const val GAMEPAD_REPORT_ID = 2
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
    private var profileProxyRequested = false
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            updateStatus(hid = "HID proxy ready", error = null)
            register(pendingMode)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            host = null
            registeredMode = null
            profileProxyRequested = false
            updateStatus(hid = "HID proxy disconnected", host = null)
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
        0x05, 0x01, 0x09, 0x04, 0xA1.toByte(), 0x01, 0x85.toByte(), GAMEPAD_REPORT_ID.toByte(),
        0xA1.toByte(), 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0A, 0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x0A, 0x75, 0x01, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75,
        0x06, 0x81.toByte(), 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15,
        0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02,
        0xC0.toByte(), 0xC0.toByte(),
    )

    fun initialize() {
        val bluetoothAdapter = adapter ?: run {
            updateStatus(hid = "Bluetooth unavailable", feedback = "Bluetooth unavailable")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            updateStatus(hid = "Bluetooth off", feedback = "Bluetooth off", host = null)
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
                    updateStatus(hid = "HID profile unavailable")
                }
            }
            startGatt()
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    fun register(mode: HidMode) {
        pendingMode = mode
        val device = hid ?: run {
            updateStatus(hid = "Waiting for HID proxy")
            return
        }

        try {
            if (registeredMode == mode) {
                updateStatus(hid = if (host == null) "HID ready (${mode.name})" else "Connected")
                return
            }
            if (registeredMode != null) {
                device.unregisterApp()
                registeredMode = null
                host = null
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
                if (mode == HidMode.MOUSE) BluetoothHidDevice.SUBCLASS1_MOUSE else BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                if (mode == HidMode.MOUSE) mouseDesc else gamepadDesc,
            )
            val modeAtRegistration = mode
            val accepted = device.registerApp(sdp, null, q, ioExecutor, object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    registeredMode = if (registered) modeAtRegistration else null
                    updateStatus(
                        hid = if (registered) "HID ready (${modeAtRegistration.name})" else "HID registration failed",
                        error = if (registered) null else "Android rejected the HID app registration.",
                    )
                }

                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    host = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                    updateStatus(
                        hid = when (state) {
                            BluetoothProfile.STATE_CONNECTING -> "Connecting"
                            BluetoothProfile.STATE_CONNECTED -> "Connected"
                            BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
                            else -> "HID ready (${modeAtRegistration.name})"
                        },
                        host = host?.safeName(),
                    )
                }
            })
            if (!accepted) {
                updateStatus(
                    hid = "HID registration rejected",
                    error = "Another app or the device firmware may already own the HID Device profile.",
                )
            }
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    fun send(mode: HidMode, report: ByteArray) {
        try {
            host?.let {
                hid?.sendReport(
                    it,
                    if (mode == HidMode.MOUSE) MOUSE_REPORT_ID else GAMEPAD_REPORT_ID,
                    report,
                )
            }
        } catch (_: SecurityException) {
            reportPermissionMissing()
        }
    }

    fun shutdown() {
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
        profileProxyRequested = false
        ioExecutor.shutdownNow()
    }

    fun reportPermissionMissing() {
        updateStatus(
            hid = "Bluetooth permission missing",
            feedback = "Bluetooth permission missing",
            error = "Grant nearby-devices Bluetooth permissions and reopen the app.",
        )
    }

    fun reportBluetoothEnableRequested() {
        updateStatus(hid = "Bluetooth enable requested", feedback = "Bluetooth enable requested")
    }

    fun reportBluetoothDisabled() {
        updateStatus(hid = "Bluetooth off", feedback = "Bluetooth off", host = null)
    }

    fun reportDiscoverable(seconds: Int) {
        updateStatus(pairing = "Discoverable for ${seconds}s", error = null)
    }

    fun reportDiscoverableRejected() {
        updateStatus(pairing = "Discoverability cancelled")
    }

    private fun startGatt() {
        if (gattServer != null) return
        updateStatus(feedback = "Opening feedback GATT")
        gattServer = try {
            btManager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                    if (service.uuid != FEEDBACK_SERVICE_UUID) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        updateStatus(feedback = "Feedback service ready")
                        startFeedbackAdvertising()
                    } else {
                        updateStatus(
                            feedback = "Feedback service failed",
                            error = "Android rejected the BLE feedback GATT service: $status.",
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
            updateStatus(feedback = "Feedback GATT unavailable")
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
            updateStatus(feedback = "Feedback service rejected")
        }
    }

    private fun startFeedbackAdvertising() {
        if (advertiseCallback != null) return
        val bluetoothAdapter = adapter ?: return
        val leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            updateStatus(feedback = "BLE advertising unavailable")
            return
        }
        advertiser = leAdvertiser
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                updateStatus(feedback = "Advertising feedback service")
            }

            override fun onStartFailure(errorCode: Int) {
                advertiseCallback = null
                updateStatus(
                    feedback = "Feedback advertising failed",
                    error = "BLE advertising failed with Android error code $errorCode.",
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
            updateStatus(feedback = "Starting feedback advertising")
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
        } catch (_: SecurityException) {
            reportPermissionMissing()
        } finally {
            advertiseCallback = null
            advertiser = null
        }
    }

    private fun BluetoothDevice.safeName(): String =
        try {
            name ?: address ?: "Host"
        } catch (_: SecurityException) {
            "Host"
        }

    private fun updateStatus(
        hid: String? = null,
        feedback: String? = null,
        pairing: String? = null,
        host: String? = _status.value.host,
        error: String? = _status.value.error,
    ) {
        _status.value = _status.value.copy(
            hid = hid ?: _status.value.hid,
            feedback = feedback ?: _status.value.feedback,
            pairing = pairing ?: _status.value.pairing,
            host = host,
            error = error,
        )
    }
}
