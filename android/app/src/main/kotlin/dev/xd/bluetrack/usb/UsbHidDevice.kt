package dev.xd.bluetrack.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import io.flutter.Log
import java.nio.ByteBuffer

class UsbHidDevice(private val context: Context) {
    companion object {
        private val TAG = UsbHidDevice::class.java.simpleName
        private const val ACTION_USB_PERMISSION = "ACTION_USB_PERMISSION"
    }

    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpoint: UsbEndpoint? = null
    private val usbRequest = UsbRequest()

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == ACTION_USB_PERMISSION) openDevice()
            }
        }

    init {
        context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun initializeUsbDevice() {
        usbDevice?.apply {
            usbInterface = getInterface(0)
            usbEndpoint = usbInterface?.getEndpoint(0)
            usbDeviceConnection =
                usbManager.openDevice(this).apply {
                    claimInterface(usbInterface, true)
                }
            usbRequest.initialize(usbDeviceConnection, usbEndpoint)
        }
    }

    suspend fun getData(): ByteArray? {
        usbDeviceConnection?.let { connection ->
            usbEndpoint?.let { endpoint ->
                usbRequest?.let { request ->
                    val bytes = ByteArray(endpoint.maxPacketSize)
                    val buffer = ByteBuffer.wrap(bytes)
                    if (request.queue(buffer, bytes.size)) {
                        connection.requestWait()
                        return bytes.take(4).toByteArray()
                    }
                }
            }
        }
        return null
    }

    fun openDevice() {
        usbManager.deviceList.values.firstOrNull()?.let {
            usbDevice = it
            if (usbManager.hasPermission(it)) {
                initializeUsbDevice()
                Log.d(TAG, "Connected to: ${it.productName}")
            } else {
                requestPermission(it)
            }
        }
    }

    fun closeConnection() {
        context.unregisterReceiver(permissionReceiver)
        usbDeviceConnection?.close()
        usbDeviceConnection = null
        usbDevice = null
    }

    fun getUsbDevice(): UsbDevice? = usbDevice
}
