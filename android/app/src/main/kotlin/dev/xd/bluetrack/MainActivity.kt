package dev.xd.bluetrack

import android.Manifest
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
            if (grants.values.all { it }) vm.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(this)
        vm = MainViewModel(container.bleGateway, container.translationEngine)
        setContent { AppScreen(vm) }
        requestBtPermissions()
    }

    override fun onDestroy() {
        if (::vm.isInitialized) vm.shutdown()
        if (::container.isInitialized) container.shutdown()
        super.onDestroy()
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            vm.start()
        }
    }
}

@Composable
private fun AppScreen(vm: MainViewModel) {
    val mode by vm.mode.collectAsState()
    val state by vm.connection.collectAsState()
    val telemetry by vm.telemetry.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF030712), Color(0xFF0A1F2E), Color(0xFF00F5A0).copy(alpha = 0.12f)))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Link: $state", color = Color(0xFF00F5A0))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gamepad", color = Color.White)
                Switch(checked = mode.name == "GAMEPAD", onCheckedChange = { vm.toggle(it) })
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
