package dev.xd.bluetrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.ui.MainViewModel
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.StickDeflection
import dev.xd.bluetrack.ui.automationLabel
import dev.xd.bluetrack.ui.diag.DiagnosticsScreen
import dev.xd.bluetrack.ui.gamepad.GamepadSurface
import dev.xd.bluetrack.ui.gamepad.rememberFrameCounterState
import dev.xd.bluetrack.ui.hosts.HostsScreen
import dev.xd.bluetrack.ui.hub.ActivityStrip
import dev.xd.bluetrack.ui.hub.GamepadShortcut
import dev.xd.bluetrack.ui.hub.Heartbeat
import dev.xd.bluetrack.ui.hub.HubHeader
import dev.xd.bluetrack.ui.hub.ModeToggle
import dev.xd.bluetrack.ui.hub.NeonRibbon
import dev.xd.bluetrack.ui.hub.PinBlock
import dev.xd.bluetrack.ui.hub.ServiceChip
import dev.xd.bluetrack.ui.hub.StatusHero
import dev.xd.bluetrack.ui.hub.TrustCard
import dev.xd.bluetrack.ui.hub.TrustState
import dev.xd.bluetrack.ui.hub.toActivityItem
import dev.xd.bluetrack.ui.relativeAgeLabel
import dev.xd.bluetrack.ui.rememberRouter
import dev.xd.bluetrack.ui.shell.ComingSoonScreen
import dev.xd.bluetrack.ui.shell.ScreenShell
import dev.xd.bluetrack.ui.shouldAutoRequestDiscoverability
import dev.xd.bluetrack.ui.stickDeflectionLabel
import dev.xd.bluetrack.ui.stickOverlayState
import dev.xd.bluetrack.ui.theme.BluetrackTheme
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
            val seconds =
                when {
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
            BluetrackTheme {
                val router = rememberRouter()
                var gamepadActive by remember { mutableStateOf(false) }
                // Lock orientation to landscape while the gamepad
                // surface is up; restore to sensor when we leave so
                // the rest of the app stays portrait-first. Using
                // `LaunchedEffect(gamepadActive)` keeps this idempotent
                // when state survives recomposition.
                LaunchedEffect(gamepadActive) {
                    requestedOrientation = if (gamepadActive) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                if (gamepadActive) {
                    val frame = rememberFrameCounterState()
                    GamepadSurface(
                        hostName = vm.status
                            .collectAsState()
                            .value.host ?: "Bluetrack",
                        seq = frame.seq,
                        pulse = frame.pulse,
                        onExit = {
                            gamepadActive = false
                            vm.toggle(false)
                        },
                        onStickMotion = { _, x, y ->
                            // Forward stick deflection through the
                            // existing mouse-delta entry point until
                            // a native gamepad axis API lands. Small
                            // scale keeps the report axis sane.
                            if (x != 0f || y != 0f) {
                                vm.processMotion(x * 12f, y * 12f, "Gamepad stick")
                            }
                        },
                        onButton = { _, _ ->
                            // Visual-only until `MainViewModel` /
                            // `TranslationEngine` expose a button
                            // bitfield setter. Logged as a follow-up
                            // in the PR.
                        },
                    )
                } else {
                    ScreenShell(router = router) { route ->
                        when (route) {
                            Route.Hub -> AppScreen(
                                vm = vm,
                                onNavigate = router::navigate,
                                onEnterGamepad = {
                                    vm.toggle(true)
                                    gamepadActive = true
                                },
                            )
                            Route.Hosts -> HostsScreen(
                                status = vm.status.collectAsState().value,
                                onConnectHost = { /* TODO: connect by id once gateway exposes it */ },
                                onDisconnectHost = { /* TODO: disconnect by id */ },
                            )
                            Route.Activity -> ComingSoonScreen("Activity")
                            Route.Diagnostics -> DiagnosticsScreen(
                                status = vm.status.collectAsState().value,
                            )
                            Route.Settings -> ComingSoonScreen("Settings")
                        }
                    }
                }
            }
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
            val permissions =
                arrayOf(
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
        val adapter =
            bluetoothAdapter() ?: run {
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
        val adapter =
            bluetoothAdapter() ?: run {
                vm.start()
                return
            }
        if (!adapter.isEnabled) {
            ensureBluetoothReady()
            return
        }
        val intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
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

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
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
    onNavigate: (Route) -> Unit = {},
    onEnterGamepad: () -> Unit = {},
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

    val running = isConnected(status)
    // Tick a session counter every time a fresh BLE feedback PIN
    // appears (each `startGatt()` rotates the PIN). Matches the
    // canvas `PinBlock session=#N` indicator without plumbing a new
    // field through `GatewayStatus`.
    var sessionCount by remember { mutableIntStateOf(0) }
    var lastPin by remember { mutableStateOf<String?>(null) }
    if (status.feedbackPin != lastPin) {
        if (status.feedbackPin != null) sessionCount += 1
        lastPin = status.feedbackPin
    }
    val trustState = if (status.trustedHostFingerprint != null) {
        TrustState.Pinned
    } else {
        TrustState.Empty
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Step 3a: canvas Hub header (`[Blue·track]` wordmark + 26 sp
        // title + FG-service chip). The neon ribbon above flashes once
        // each time a fresh feedback PIN is issued — equivalent to the
        // canvas `NeonRibbon` keyed on a new GATT session.
        NeonRibbon(trigger = status.feedbackPin)
        HubHeader(
            title = "Hub",
            rightSlot = { ServiceChip(running = running) },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusHero(
                connected = isConnected(status),
                hostName = status.host,
                metric = if (isConnected(status)) {
                    "HID · ${compactCount(status.reportsSent)} reports · ${status.rejectedFeedbackPackets} dropped"
                } else {
                    null
                },
            )
            // Compatibility / fallback rows kept as ConnectionPanel
            // below the hero so the host fallback, input live label,
            // and any error message stay one tap away.
            ConnectionPanel(status = status, now = now)
            PinBlock(
                pin = status.feedbackPin,
                session = sessionCount,
                gattOpen = status.feedbackPin != null,
            )
            TrustCard(
                state = trustState,
                fingerprint = status.trustedHostFingerprint,
                onForget = { vm.forgetTrustedHost() },
                onShowQR = { /* TODO: identity QR sheet — follow-up after --export-identity CLI lands */ },
            )
            ModeToggle(
                mode = mode,
                onToggle = { next -> vm.toggle(next == HidMode.GAMEPAD) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    label = "Reports",
                    value = compactCount(status.reportsSent),
                    modifier = Modifier.weight(1f),
                    subtitle = relativeAgeLabel(now, status.lastReportAtMs),
                )
                MetricTile(
                    label = "Feedback",
                    value = status.feedbackPackets.toString(),
                    modifier = Modifier.weight(1f),
                    subtitle = relativeAgeLabel(now, status.lastFeedbackAtMs),
                )
            }
            TouchpadPanel(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                mode = mode,
                telemetryX = telemetry.stickX,
                telemetryY = telemetry.stickY,
                onTouchStart = { vm.beginTouchGesture() },
                onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
            )
            SystemPanel(
                status = status,
                modifier = Modifier.fillMaxWidth(),
            )
            GamepadShortcut(onEnter = onEnterGamepad)
            ActivityStrip(
                items = status.events.take(4).map { it.toActivityItem(relativeAgeLabel(now, it.timestampMs)) },
                onOpen = { onNavigate(Route.Activity) },
            )
            Heartbeat(active = isConnected(status))
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
    mode: HidMode,
    telemetryX: Int,
    telemetryY: Int,
    onTouchStart: () -> Unit,
    onMotion: (Float, Float, String) -> Unit,
) {
    val isGamepad = mode == HidMode.GAMEPAD
    val stick = stickOverlayState(stickX = telemetryX, stickY = telemetryY)
    val palette = BluetrackTheme.palette
    val dotColor =
        when {
            !isGamepad -> Color(0xFF00F5A0)
            stick.deflection == StickDeflection.IDLE -> Color.White.copy(alpha = 0.55f)
            stick.deflection == StickDeflection.LIGHT -> Color(0xFF00E5FF)
            else -> Color(0xFF00F5A0)
        }
    // Step 4 liquid-drop visual state. The AndroidView below still
    // owns the actual HID emission pipeline; this Compose-side
    // state only feeds the on-screen radial gradient + trail so
    // touch reads as a glowing drop following the finger. The
    // trail is capped at 20 points so allocation is bounded.
    val pointer = remember { mutableStateOf<Offset?>(null) }
    val trail = remember { mutableStateListOf<Offset>() }
    Panel(modifier) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val baseRadius = minOf(size.width, size.height) * 0.27f
                val travelRadius = minOf(size.width, size.height) * 0.42f
                drawCircle(Color(0x2400E5FF), baseRadius, Offset(cx, cy))
                drawLine(Color(0xFF00E5FF), Offset(cx - baseRadius * 1.3f, cy), Offset(cx + baseRadius * 1.3f, cy), 2f)
                drawLine(Color(0xFF00E5FF), Offset(cx, cy - baseRadius * 1.3f), Offset(cx, cy + baseRadius * 1.3f), 2f)
                if (isGamepad) {
                    val ringColor = Color.White.copy(alpha = 0.22f)
                    drawCircle(ringColor, travelRadius, Offset(cx, cy), style = Stroke(width = 2f))
                    drawCircle(ringColor, baseRadius * 0.4f, Offset(cx, cy), style = Stroke(width = 1.5f))
                }
                val dotOffset =
                    if (isGamepad) {
                        Offset(cx + stick.normalizedX * travelRadius, cy + stick.normalizedY * travelRadius)
                    } else {
                        Offset(cx + telemetryX * 1.5f, cy + telemetryY * 1.5f)
                    }
                drawCircle(dotColor, if (isGamepad) 14f else 13f, dotOffset)
            }
            // Liquid-drop overlay (mouse mode only). Drawn above
            // the base grid but below the gamepad label + the
            // touch-capture AndroidView (which sits transparent on
            // top). Trail polyline first, then the radial drop at
            // the current pointer.
            if (!isGamepad && (trail.isNotEmpty() || pointer.value != null)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (trail.size > 1) {
                        for (i in 1 until trail.size) {
                            val a = trail[i - 1]
                            val b = trail[i]
                            val alpha = i.toFloat() / trail.size
                            drawLine(
                                color = palette.mintBright.copy(alpha = alpha * 0.85f),
                                start = a,
                                end = b,
                                strokeWidth = 3f,
                            )
                        }
                    }
                    pointer.value?.let { p ->
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to palette.mintBright,
                                    0.5f to palette.mint,
                                    0.75f to palette.mintGlow,
                                    1f to Color.Transparent,
                                ),
                                center = p,
                                radius = 36f,
                            ),
                            radius = 36f,
                            center = p,
                        )
                    }
                }
            }
            if (isGamepad) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stickDeflectionLabel(stick.deflection),
                        color = dotColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "X ${stick.xLabel}  Y ${stick.yLabel}",
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }
            AndroidView(factory = { ctx ->
                FrameLayout(ctx).apply {
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
                        } else {
                            false
                        }
                    }
                    setOnTouchListener { _, ev ->
                        var batchX = 0f
                        var batchY = 0f

                        fun processPoint(
                            x: Float,
                            y: Float,
                        ) {
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
                                pointer.value = Offset(ev.x, ev.y)
                                trail.clear()
                                trail.add(Offset(ev.x, ev.y))
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                for (i in 0 until ev.historySize) {
                                    processPoint(ev.getHistoricalX(i), ev.getHistoricalY(i))
                                }
                                processPoint(ev.x, ev.y)
                                emitBatch()
                                pointer.value = Offset(ev.x, ev.y)
                                if (trail.size >= 20) trail.removeAt(0)
                                trail.add(Offset(ev.x, ev.y))
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                parent.requestDisallowInterceptTouchEvent(false)
                                performClick()
                                pointer.value = null
                                trail.clear()
                                true
                            }
                            else -> false
                        }
                    }
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SystemPanel(
    status: GatewayStatus,
    modifier: Modifier,
) {
    // PIN and Trust rows moved to dedicated `PinBlock` + `TrustCard`
    // cards above (UI port step 3b). System panel keeps the four
    // transport-state rows it always had.
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
private fun StatusLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.62f), modifier = Modifier.width(74.dp))
        Text(value, color = Color.White, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier,
    subtitle: String? = null,
) {
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Color.White.copy(alpha = 0.62f))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        content = content,
    )
}

private fun primaryStatus(
    status: GatewayStatus,
    now: Long,
): String = when {
    status.error != null -> "Needs attention"
    isConnected(status) && isInputLive(status, now) -> "Ready - input live"
    isConnected(status) -> "Ready"
    status.hid.contains("connecting", ignoreCase = true) ||
        status.pairing.contains("connecting", ignoreCase = true) -> "Connecting"
    status.pairing.contains("discoverable", ignoreCase = true) ||
        status.pairing.contains("pairing", ignoreCase = true) -> "Pairing"
    else -> "Preparing"
}

private fun hostFallback(status: GatewayStatus): String = when {
    status.compatibility.bondedDevices.isNotEmpty() -> "Bonded"
    status.pairing.contains("discoverable", ignoreCase = true) -> "Pairing"
    else -> "Searching"
}

private fun inputLabel(
    status: GatewayStatus,
    now: Long,
): String = when {
    isInputLive(status, now) -> "${status.lastInputSource ?: "Input"} live"
    status.lastInputSource != null -> status.lastInputSource
    else -> "Idle"
}

private fun isConnected(status: GatewayStatus): Boolean = status.host != null ||
    status.hid.contains("connected", ignoreCase = true) ||
    status.pairing.contains("HID connected", ignoreCase = true)

private fun isInputLive(
    status: GatewayStatus,
    now: Long,
): Boolean = status.lastInputAtMs?.let { now - it < 1400L } == true

private fun compactCount(value: Int): String =
    if (value < 1000) value.toString() else "${value / 1000}.${(value % 1000) / 100}k"
