package dev.xd.bluetrack.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.xd.bluetrack.bluetooth.hid.HidDevice
import dev.xd.bluetrack.usb.UsbHidDevice
import io.flutter.Log
import io.flutter.plugin.common.MethodChannel
import android.hardware.usb.*

class BluetoothHandler(
    private val context: Context,
    private val methodChannel: MethodChannel,
    private val usbHidDevice: UsbHidDevice,
) : BluetoothProfile.ServiceListener {
    companion object {
        private val TAG = BluetoothHandler::class.java.simpleName

        private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        private var hidDevice: HidDevice? = null

        private var deviceAddress: String? = null
    }
 
    override fun onServiceConnected(
        profile: Int,
        proxy: BluetoothProfile,
    ) {
        if (profile != BluetoothProfile.HID_DEVICE) return

        deviceAddress?.let {
            Log.d(TAG, "Connecting to: $it")
            hidDevice = HidDevice(context, methodChannel, proxy as BluetoothHidDevice, bluetoothAdapter.getRemoteDevice(it), usbHidDevice)
        }
    }

    override fun onServiceDisconnected(profile: Int) {
        if (profile != BluetoothProfile.HID_DEVICE) return

        Log.d(TAG, "Reconnecting to service")
        connect(deviceAddress)
    }

    fun connect(address: String?) {
        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "Bluetooth is not enabled")
            return
        }

        address?.let {
            deviceAddress = it
        }
        if (!bluetoothAdapter.getProfileProxy(context, this, BluetoothProfile.HID_DEVICE)) {
            Log.d(TAG, "Bluetooth HID profile is not supported")
        }
    }

    fun getHidDevice(): HidDevice? = hidDevice
}
