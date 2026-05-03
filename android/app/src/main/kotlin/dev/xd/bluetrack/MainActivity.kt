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
import dev.xd.bluetrack.ble.CompatibilitySnapshot
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
        HeaderPanel(mode = mode.name, onGamepadChanged = { vm.toggle(it) })
        ActionPanel(status = status)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Reports", status.reportsSent.toString(), Modifier.weight(1f))
            MetricTile("Feedback", status.feedbackPackets.toString(), Modifier.weight(1f))
            MetricTile("Rejected", status.rejectedFeedbackPackets.toString(), Modifier.weight(1f))
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
                        onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
                    )
                    Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompatibilityPanel(status.compatibility, Modifier.weight(1f).fillMaxHeight())
                        TimelinePanel(status.events, now, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TouchpadPanel(
                        modifier = Modifier.weight(1.15f).fillMaxHeight(),
                        telemetryX = telemetry.stickX,
                        telemetryY = telemetry.stickY,
                        onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
                    )
                    Column(
                        modifier = Modifier.weight(0.85f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompatibilityPanel(status.compatibility, Modifier.weight(1f))
                        TimelinePanel(status.events, now, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderPanel(mode: String, onGamepadChanged: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Bluetrack Cockpit", color = Color(0xFF00F5A0), fontWeight = FontWeight.Bold)
                Text("Mode $mode", color = Color.White.copy(alpha = 0.7f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gamepad", color = Color.White)
                Switch(checked = mode == "GAMEPAD", onCheckedChange = onGamepadChanged)
            }
        }
    }
}

@Composable
private fun ActionPanel(
    status: GatewayStatus,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine("Auto", status.automationLabel())
            StatusLine("HID", status.hid)
            StatusLine("Feedback", status.feedback)
            StatusLine("Pairing", status.pairing)
            status.host?.let { StatusLine("Host", it) }
            StatusLine("Input", status.lastInputSource ?: "Waiting")
            status.error?.let { Text(it, color = Color(0xFFFFB4AB)) }
        }
    }
}

@Composable
private fun TouchpadPanel(
    modifier: Modifier,
    telemetryX: Int,
    telemetryY: Int,
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
                    fun processPoint(x: Float, y: Float) {
                        val dx = ((x - lastX) * 0.42f).coerceIn(-22f, 22f)
                        val dy = ((y - lastY) * 0.42f).coerceIn(-22f, 22f)
                        lastX = x
                        lastY = y
                        filteredX = filteredX * 0.18f + dx * 0.82f
                        filteredY = filteredY * 0.18f + dy * 0.82f
                        if (abs(filteredX) > 0.04f || abs(filteredY) > 0.04f) {
                            onMotion(filteredX, filteredY, "Touchpad")
                        }
                    }
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            parent.requestDisallowInterceptTouchEvent(true)
                            requestFocus()
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
            Column(
                Modifier.align(Alignment.TopStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Input Surface", color = Color.White, fontWeight = FontWeight.Bold)
                Text("x $telemetryX  y $telemetryY", color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun CompatibilityPanel(snapshot: CompatibilitySnapshot, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Compatibility", color = Color.White, fontWeight = FontWeight.Bold)
            StatusLine("Adapter", yesNo(snapshot.bluetoothAvailable))
            StatusLine("Enabled", yesNo(snapshot.bluetoothEnabled))
            StatusLine("Advertiser", optionalYesNo(snapshot.bleAdvertiserAvailable))
            StatusLine("Multi adv", optionalYesNo(snapshot.multipleAdvertisementSupported))
            StatusLine("HID", snapshot.hidProfile)
            StatusLine("Scan", snapshot.scanMode)
            StatusLine("Bonded", snapshot.bondedDevices.size.toString())
            snapshot.bondedDevices.take(4).forEach { device ->
                Text(device, color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun TimelinePanel(events: List<GatewayEvent>, now: Long, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Timeline", color = Color.White, fontWeight = FontWeight.Bold)
            if (events.isEmpty()) {
                Text("No events yet", color = Color.White.copy(alpha = 0.7f))
            } else {
                events.forEach { event ->
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
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content,
    )
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private fun optionalYesNo(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}

private fun ageLabel(now: Long, timestampMs: Long): String {
    val seconds = ((now - timestampMs) / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
}
