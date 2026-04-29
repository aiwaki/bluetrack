package dev.xd.bluetrack.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BleHidGateway(private val context: Context, private val engine: TranslationEngine) {
    private val decryptor = PayloadDecryptor()
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow("Idle")
    val state: StateFlow<String> = _state

    private val mouseDesc = byteArrayOf(0x05,0x01,0x09,0x02,0xA1.toByte(),0x01,0x09,0x01,0xA1.toByte(),0x00,0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95.toByte(),0x03,0x75,0x01,0x81.toByte(),0x02,0x95.toByte(),0x01,0x75,0x05,0x81.toByte(),0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81.toByte(),0x25,0x7F,0x75,0x08,0x95.toByte(),0x03,0x81.toByte(),0x06,0xC0.toByte(),0xC0.toByte())
    private val gamepadDesc = byteArrayOf(0x05,0x01,0x09,0x04,0xA1.toByte(),0x01,0xA1.toByte(),0x00,0x05,0x09,0x19,0x01,0x29,0x0A,0x15,0x00,0x25,0x01,0x95.toByte(),0x0A,0x75,0x01,0x81.toByte(),0x02,0x95.toByte(),0x01,0x75,0x06,0x81.toByte(),0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x15,0x81.toByte(),0x25,0x7F,0x75,0x08,0x95.toByte(),0x02,0x81.toByte(),0x02,0xC0.toByte(),0xC0.toByte())

    fun initialize() {
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { hid = proxy as BluetoothHidDevice; _state.value = "HID Ready" }
            override fun onServiceDisconnected(profile: Int) { hid = null; _state.value = "HID Disconnected" }
        }, BluetoothProfile.HID_DEVICE)
        startGatt()
    }

    fun register(mode: HidMode) {
        val q = BluetoothHidDeviceAppQosSettings(BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED, 800, 9, 0, 11250, 11250)
        val sdp = BluetoothHidDeviceAppSdpSettings("Bluetrack Pro Engine", "MITM HID", "Bluetrack", if (mode == HidMode.MOUSE) BluetoothHidDevice.SUBCLASS1_MOUSE else BluetoothHidDevice.SUBCLASS1_JOYSTICK, if (mode == HidMode.MOUSE) mouseDesc else gamepadDesc)
        hid?.registerApp(sdp, null, q, ioExecutor, object : BluetoothHidDevice.Callback() {
            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                host = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                _state.value = if (host == null) "Standby" else "Connected"
            }
        })
    }

    fun send(mode: HidMode, report: ByteArray) { host?.let { hid?.sendReport(it, if (mode == HidMode.MOUSE) 1 else 2, report) } }

    private fun startGatt() {
        val gatt = btManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                decryptor.decryptPayloadTo(value) { correctionX, correctionY ->
                    engine.updateCorrection(correctionX, correctionY)
                }
            }
        })
        val service = BluetoothGattService(UUID.fromString("0d03f2a3-b9b2-43f6-90ca-6c4ff67c2263"), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString("4846ff87-f2d4-4df2-9500-9bf8ed23f9e6"), BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        gatt?.addService(service)
    }
}
