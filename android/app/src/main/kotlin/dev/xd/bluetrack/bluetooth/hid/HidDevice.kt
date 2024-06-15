package dev.xd.bluetrack.bluetooth.hid

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.content.Context
import io.flutter.Log
import io.flutter.plugin.common.MethodChannel
import dev.xd.bluetrack.usb.UsbHidDevice
import kotlinx.coroutines.*

class HidDevice(
    private val context: Context,
    private val methodChannel: MethodChannel,
    private val service: BluetoothHidDevice,
    private val device: BluetoothDevice,
    private val usbHidDevice: UsbHidDevice,
) : BluetoothHidDevice.Callback() {
    companion object {
        private val TAG = HidDevice::class.java.simpleName

        private const val REPORT_ID = 1
        private const val MOUSE_NAME = "Mouse"
        private const val MOUSE_PROVIDER = "Mouse Provider"
        private const val MOUSE_DESCRIPTION = "Bluetooth Mouse"
    }

    private val SDP_NAME = usbHidDevice.getUsbDevice()?.productName ?: MOUSE_NAME
    private val SDP_PROVIDER = usbHidDevice.getUsbDevice()?.manufacturerName ?: MOUSE_PROVIDER
    private val SDP_DESCRIPTION = "$SDP_NAME $SDP_PROVIDER" ?: MOUSE_DESCRIPTION

    init {
        BluetoothHidDeviceAppSdpSettings(
            SDP_NAME,
            SDP_DESCRIPTION,
            SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HidDescriptor.getDescriptor(),
        ).apply {
            service.registerApp(this, null, null, context.mainExecutor, this@HidDevice)
        }

        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                    send()
                    delay(0L)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending the report: ${e.message}")
                }
            }
        }
    }

    override fun onAppStatusChanged(
        pluggedDevice: BluetoothDevice?,
        registered: Boolean,
    ) {
        pluggedDevice?.let {
            service.connect(device)
        }
    }

    override fun onConnectionStateChanged(
        device: BluetoothDevice,
        state: Int,
    ) {
        when (state) {
            BluetoothHidDevice.STATE_CONNECTED -> {
                Log.d(TAG, "HID Host connected")
                methodChannel.invokeMethod("onConnected", device.address)
            }
            BluetoothHidDevice.STATE_DISCONNECTED -> {
                Log.d(TAG, "HID Host disconnected")
                methodChannel.invokeMethod("onDisconnected", null)
            }
            else -> super.onConnectionStateChanged(device, state)
        }
    }

    fun disconnect(address: String?) {
        address?.takeIf { it == device.address }?.also {
            service.disconnect(device)
            service.unregisterApp()
        }
    }

    suspend fun send() {
        usbHidDevice.getData()?.let {
            service.sendReport(device, REPORT_ID, it)
        }
    }
}