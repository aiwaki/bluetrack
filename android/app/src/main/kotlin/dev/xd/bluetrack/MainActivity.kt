package dev.xd.bluetrack

import androidx.annotation.NonNull
import dev.xd.bluetrack.bluetooth.BluetoothHandler
import dev.xd.bluetrack.usb.UsbHidDevice
import io.flutter.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val CHANNEL = "$TAG/channel"

        private lateinit var methodChannel: MethodChannel
        private lateinit var bluetoothHandler: BluetoothHandler
        private lateinit var usbHidDevice: UsbHidDevice

        private fun isInitialized() = ::bluetoothHandler.isInitialized
    }

    override fun configureFlutterEngine(
        @NonNull flutterEngine: FlutterEngine,
    ) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)

        usbHidDevice = UsbHidDevice(this)
        usbHidDevice.openDevice()

        bluetoothHandler = BluetoothHandler(this, methodChannel, usbHidDevice)

        methodChannel.setMethodCallHandler { call, result ->
            if (!isInitialized()) result.error("error", "Bluetooth is not initialized", null)

            Log.d(TAG, "Method called: ${call.method}")

            when (call.method) {
                "connect" -> bluetoothHandler.connect(call.argument<String>("address") ?: "")
                "disconnect" -> bluetoothHandler.getHidDevice()?.disconnect(call.argument<String>("address") ?: "")
                else -> result.notImplemented()
            }
        }
    }

    override fun cleanUpFlutterEngine(
        @NonNull flutterEngine: FlutterEngine,
    ) {
        super.cleanUpFlutterEngine(flutterEngine)
        methodChannel.setMethodCallHandler(null)
        usbHidDevice.closeConnection()
    }
}
