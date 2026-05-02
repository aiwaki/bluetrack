package dev.xd.bluetrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xd.bluetrack.core.AppContainer
import dev.xd.bluetrack.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel
    private lateinit var container: AppContainer
    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                ensureBluetoothReady()
            } else {
                vm.bluetoothPermissionMissing()
            }
        }
    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothEnabled()) {
                vm.start()
            } else {
                vm.bluetoothDisabled()
            }
        }
    private val discoverableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val seconds = when {
                result.resultCode > 0 -> result.resultCode
                result.resultCode == RESULT_OK -> 300
                else -> 0
            }
            if (seconds > 0) {
                vm.discoverable(seconds)
            } else {
                vm.discoverabilityCancelled()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(this)
        vm = MainViewModel(container.bleGateway, container.translationEngine)
        setContent {
            AppScreen(
                vm = vm,
                onEnableBluetooth = ::ensureBluetoothReady,
                onStartPairing = ::requestDiscoverability,
            )
        }
        requestBtPermissions()
    }

    override fun onDestroy() {
        if (::vm.isInitialized) vm.shutdown()
        if (::container.isInitialized) container.shutdown()
        super.onDestroy()
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
            if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                ensureBluetoothReady()
            } else {
                bluetoothPermissions.launch(permissions)
            }
        } else {
            ensureBluetoothReady()
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothReady() {
        val adapter = bluetoothAdapter() ?: run {
            vm.start()
            return
        }
        if (!adapter.isEnabled) {
            vm.bluetoothEnableRequested()
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        vm.start()
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverability() {
        if (!hasBluetoothPermissions()) {
            requestBtPermissions()
            return
        }
        val adapter = bluetoothAdapter() ?: run {
            vm.start()
            return
        }
        if (!adapter.isEnabled) {
            ensureBluetoothReady()
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        discoverableBluetooth.launch(intent)
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean = bluetoothAdapter()?.isEnabled == true

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
}

@Composable
private fun AppScreen(
    vm: MainViewModel,
    onEnableBluetooth: () -> Unit,
    onStartPairing: () -> Unit,
) {
    val mode by vm.mode.collectAsState()
    val status by vm.status.collectAsState()
    val telemetry by vm.telemetry.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF030712), Color(0xFF0A1F2E), Color(0xFF00F5A0).copy(alpha = 0.12f)))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Bluetrack", color = Color(0xFF00F5A0))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gamepad", color = Color.White)
                Switch(checked = mode.name == "GAMEPAD", onCheckedChange = { vm.toggle(it) })
            }
        }
        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine("HID", status.hid)
            StatusLine("Feedback", status.feedback)
            StatusLine("Pairing", status.pairing)
            status.host?.let { StatusLine("Host", it) }
            status.error?.let { Text(it, color = Color(0xFFFFB4AB)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStartPairing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5A0), contentColor = Color(0xFF031018)),
                ) {
                    Text("Pair with PC")
                }
                OutlinedButton(onClick = onEnableBluetooth, modifier = Modifier.weight(1f)) {
                    Text("Enable Bluetooth")
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))) {
            Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                val cx = size.width / 2f; val cy = size.height / 2f
                drawCircle(Color(0x2200E5FF), 180f, Offset(cx, cy))
                drawLine(Color(0xFF00E5FF), Offset(cx - 220f, cy), Offset(cx + 220f, cy), 2f)
                drawLine(Color(0xFF00E5FF), Offset(cx, cy - 220f), Offset(cx, cy + 220f), 2f)
                drawCircle(Color(0xFF00F5A0), 12f, Offset(cx + telemetry.stickX * 1.5f, cy + telemetry.stickY * 1.5f))
            }
            AndroidView(factory = { ctx -> FrameLayout(ctx).apply {
                isFocusableInTouchMode = true
                setOnGenericMotionListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_HOVER_MOVE && ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
                        vm.processMotion(ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X), ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)); true
                    } else false
                }
            } }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.62f), modifier = Modifier.width(74.dp))
        Text(value, color = Color.White, modifier = Modifier.weight(1f))
    }
}
