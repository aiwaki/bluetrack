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
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xd.bluetrack.ble.GatewayEvent
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.ui.MainViewModel
import dev.xd.bluetrack.ui.automationLabel
import dev.xd.bluetrack.ui.shouldAutoRequestDiscoverability
import kotlinx.coroutines.delay
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel
    private var autoBluetoothEnableRequested = false
    private var autoDiscoverabilityRequested = false
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
                autoBluetoothEnableRequested = false
                ensureKeepAliveService()
                vm.start()
                maybeRequestDiscoverability()
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
        val container = (application as BluetrackApplication).container
        vm = MainViewModel(container.bleGateway, container.translationEngine)
        setContent {
            AppScreen(vm = vm)
        }
        requestBtPermissions()
    }

    override fun onDestroy() {
        if (::vm.isInitialized) vm.detach()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) {
            if (hasBluetoothPermissions()) {
                ensureBluetoothReady()
            } else {
                vm.refreshCompatibility()
            }
        }
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
            if (!autoBluetoothEnableRequested) {
                autoBluetoothEnableRequested = true
                vm.bluetoothEnableRequested()
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                vm.bluetoothDisabled()
            }
            return
        }
        autoBluetoothEnableRequested = false
        ensureKeepAliveService()
        vm.start()
        maybeRequestDiscoverability()
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverability(auto: Boolean = false) {
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
        vm.discoverabilityRequested(auto)
        discoverableBluetooth.launch(intent)
    }

    private fun maybeRequestDiscoverability() {
        if (autoDiscoverabilityRequested || !::vm.isInitialized) return
        if (!hasBluetoothPermissions() || !isBluetoothEnabled()) return
        if (!vm.status.value.shouldAutoRequestDiscoverability()) return

        autoDiscoverabilityRequested = true
        requestDiscoverability(auto = true)
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

    private fun ensureKeepAliveService() {
        val intent = Intent(this, HidKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            startService(intent)
        }
    }
}

@Composable
private fun AppScreen(
    vm: MainViewModel,
) {
    val mode by vm.mode.collectAsState()
    val status by vm.status.collectAsState()
    val telemetry by vm.telemetry.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF031018), Color(0xFF102333), Color(0xFF07140F))))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderPanel(mode = mode.name, status = status, now = now, onGamepadChanged = { vm.toggle(it) })
        ConnectionPanel(status = status, now = now)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Mode", mode.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
            MetricTile("Reports", compactCount(status.reportsSent), Modifier.weight(1f))
            MetricTile("Feedback", status.feedbackPackets.toString(), Modifier.weight(1f))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth < 620.dp) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TouchpadPanel(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        telemetryX = telemetry.stickX,
                        telemetryY = telemetry.stickY,
                        onTouchStart = { vm.beginTouchGesture() },
                        onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
                    )
                    Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SystemPanel(status, Modifier.weight(1f).fillMaxHeight())
                        TimelinePanel(status.events, now, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TouchpadPanel(
                        modifier = Modifier.weight(1.15f).fillMaxHeight(),
                        telemetryX = telemetry.stickX,
                        telemetryY = telemetry.stickY,
                        onTouchStart = { vm.beginTouchGesture() },
                        onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
                    )
                    Column(
                        modifier = Modifier.weight(0.85f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SystemPanel(status, Modifier.weight(0.75f))
                        TimelinePanel(status.events, now, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderPanel(mode: String, status: GatewayStatus, now: Long, onGamepadChanged: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Bluetrack", color = Color(0xFF00F5A0), fontWeight = FontWeight.Bold)
                Text(primaryStatus(status, now), color = primaryStatusColor(status, now))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gamepad", color = Color.White)
                Switch(checked = mode == "GAMEPAD", onCheckedChange = onGamepadChanged)
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    status: GatewayStatus,
    now: Long,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine("State", primaryStatus(status, now))
            StatusLine("Host", status.host ?: hostFallback(status))
            StatusLine("Input", inputLabel(status, now))
            StatusLine("Flow", status.automationLabel())
            status.error?.let { Text(it, color = Color(0xFFFFB4AB)) }
        }
    }
}

@Composable
private fun TouchpadPanel(
    modifier: Modifier,
    telemetryX: Int,
    telemetryY: Int,
    onTouchStart: () -> Unit,
    onMotion: (Float, Float, String) -> Unit,
) {
    Panel(modifier) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                val cx = size.width / 2f; val cy = size.height / 2f
                val radius = minOf(size.width, size.height) * 0.27f
                drawCircle(Color(0x2400E5FF), radius, Offset(cx, cy))
                drawLine(Color(0xFF00E5FF), Offset(cx - radius * 1.3f, cy), Offset(cx + radius * 1.3f, cy), 2f)
                drawLine(Color(0xFF00E5FF), Offset(cx, cy - radius * 1.3f), Offset(cx, cy + radius * 1.3f), 2f)
                drawCircle(Color(0xFF00F5A0), 13f, Offset(cx + telemetryX * 1.5f, cy + telemetryY * 1.5f))
            }
            AndroidView(factory = { ctx -> FrameLayout(ctx).apply {
                var lastX = 0f
                var lastY = 0f
                var filteredX = 0f
                var filteredY = 0f
                isFocusableInTouchMode = true
                isClickable = true
                setOnGenericMotionListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_HOVER_MOVE && ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
                        onMotion(
                            ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                            ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),
                            "External mouse",
                        )
                        true
                    } else false
                }
                setOnTouchListener { _, ev ->
                    var batchX = 0f
                    var batchY = 0f

                    fun processPoint(x: Float, y: Float) {
                        val dx = ((x - lastX) * 0.42f).coerceIn(-22f, 22f)
                        val dy = ((y - lastY) * 0.42f).coerceIn(-22f, 22f)
                        lastX = x
                        lastY = y
                        filteredX = filteredX * 0.18f + dx * 0.82f
                        filteredY = filteredY * 0.18f + dy * 0.82f
                        if (abs(filteredX) > 0.04f || abs(filteredY) > 0.04f) {
                            batchX += filteredX
                            batchY += filteredY
                        }
                    }

                    fun emitBatch() {
                        if (abs(batchX) > 0.04f || abs(batchY) > 0.04f) {
                            onMotion(batchX, batchY, "Touchpad")
                        }
                    }

                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            parent.requestDisallowInterceptTouchEvent(true)
                            requestFocus()
                            onTouchStart()
                            lastX = ev.x
                            lastY = ev.y
                            filteredX = 0f
                            filteredY = 0f
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            for (i in 0 until ev.historySize) {
                                processPoint(ev.getHistoricalX(i), ev.getHistoricalY(i))
                            }
                            processPoint(ev.x, ev.y)
                            emitBatch()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            parent.requestDisallowInterceptTouchEvent(false)
                            performClick()
                            true
                        }
                        else -> false
                    }
                }
            } }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SystemPanel(status: GatewayStatus, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("System", color = Color.White, fontWeight = FontWeight.Bold)
            StatusLine("BT", if (status.compatibility.bluetoothEnabled) "Ready" else "Off")
            StatusLine("HID", status.hid)
            StatusLine("Pair", status.pairing)
            StatusLine("BLE", status.feedback)
        }
    }
}

@Composable
private fun TimelinePanel(events: List<GatewayEvent>, now: Long, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Activity", color = Color.White, fontWeight = FontWeight.Bold)
            if (events.isEmpty()) {
                Text("Quiet", color = Color.White.copy(alpha = 0.7f))
            } else {
                events.take(5).forEach { event ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ageLabel(now, event.timestampMs), color = Color(0xFF00E5FF))
                            Text(event.source, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Text(event.message, color = Color.White.copy(alpha = 0.78f))
                    }
                }
            }
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

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier) {
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Color.White.copy(alpha = 0.62f))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        content = content,
    )
}

private fun primaryStatus(status: GatewayStatus, now: Long): String = when {
    status.error != null -> "Needs attention"
    isConnected(status) && isInputLive(status, now) -> "Ready - input live"
    isConnected(status) -> "Ready"
    status.hid.contains("connecting", ignoreCase = true) ||
        status.pairing.contains("connecting", ignoreCase = true) -> "Connecting"
    status.pairing.contains("discoverable", ignoreCase = true) ||
        status.pairing.contains("pairing", ignoreCase = true) -> "Pairing"
    else -> "Preparing"
}

private fun primaryStatusColor(status: GatewayStatus, now: Long): Color = when {
    status.error != null -> Color(0xFFFFB4AB)
    isConnected(status) && isInputLive(status, now) -> Color(0xFF00F5A0)
    isConnected(status) -> Color(0xFF00E5FF)
    else -> Color.White.copy(alpha = 0.72f)
}

private fun hostFallback(status: GatewayStatus): String = when {
    status.compatibility.bondedDevices.isNotEmpty() -> "Bonded"
    status.pairing.contains("discoverable", ignoreCase = true) -> "Pairing"
    else -> "Searching"
}

private fun inputLabel(status: GatewayStatus, now: Long): String = when {
    isInputLive(status, now) -> "${status.lastInputSource ?: "Input"} live"
    status.lastInputSource != null -> status.lastInputSource
    else -> "Idle"
}

private fun isConnected(status: GatewayStatus): Boolean =
    status.host != null ||
        status.hid.contains("connected", ignoreCase = true) ||
        status.pairing.contains("HID connected", ignoreCase = true)

private fun isInputLive(status: GatewayStatus, now: Long): Boolean =
    status.lastInputAtMs?.let { now - it < 1400L } == true

private fun compactCount(value: Int): String =
    if (value < 1000) value.toString() else "${value / 1000}.${(value % 1000) / 100}k"

private fun ageLabel(now: Long, timestampMs: Long): String {
    val seconds = ((now - timestampMs) / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
}
