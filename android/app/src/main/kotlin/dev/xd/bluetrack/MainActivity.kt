package dev.xd.bluetrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import dev.xd.bluetrack.ble.BluetoothHostKind
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.ui.MainViewModel
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.StickDeflection
import dev.xd.bluetrack.ui.activity.ActivityScreen
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
import dev.xd.bluetrack.ui.settings.SettingsScreen
import dev.xd.bluetrack.ui.settings.TweaksRepository
import dev.xd.bluetrack.ui.settings.TweaksState
import dev.xd.bluetrack.ui.shell.ScreenShell
import dev.xd.bluetrack.ui.shouldAutoRequestDiscoverability
import dev.xd.bluetrack.ui.stickDeflectionLabel
import dev.xd.bluetrack.ui.stickOverlayState
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.welcome.WelcomeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.graphics.Color as AndroidColor

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
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The grant outcome is informational only — we never
            // block the HID path on the notifications grant.
            // `SettingsScreen.PERMISSIONS` reflects the new state
            // on the next composition via `hasNotificationsPermission`.
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

    private lateinit var tweaksRepo: TweaksRepository
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * Conflated channel for pending [TweaksState] writes. Codex
     * review on PR #53 flagged that the earlier "launch + edit"
     * sequence could let stale snapshots interleave during a fast
     * slider drag and revert newer values. A single collector on
     * [ioScope] drains the latest pending state; intermediate
     * values are dropped (DROP_OLDEST) so DataStore is never
     * doing more work than the latest user input demands.
     */
    private val pendingTweaks = MutableSharedFlow<TweaksState>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: status + navigation bars go transparent
        // so the app's dark background bleeds through and the
        // system bars stop reading as a foreign band on top /
        // bottom. `SystemBarStyle.dark(...)` forces light icons,
        // which is what we want against the Bluetrack dark
        // palette (`bg0`). Must be called before `super.onCreate`.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as BluetrackApplication).container
        vm = MainViewModel(container.bleGateway, container.translationEngine)
        tweaksRepo = TweaksRepository(applicationContext)
        // Drain pending tweaks on a single coroutine so writes
        // happen in arrival order and never race.
        ioScope.launch {
            pendingTweaks.collect { state -> tweaksRepo.setAll(state) }
        }
        setContent {
            BluetrackTheme {
                val router = rememberRouter()
                var gamepadActive by remember { mutableStateOf(false) }
                // Visual tweaks are now fixed at the design baseline
                // — glass surfaces off (the UI reads cleaner flat),
                // motion not reduced, no aurora-on-low-battery, neon
                // at full strength. Only `autoConnectEnabled` is
                // surfaced through DataStore today; the rest of the
                // `TweaksRepository` plumbing is kept for forward-
                // compat (a future advanced page could expose them
                // again) but the Settings route no longer surfaces
                // those toggles.
                val autoConnectEnabled by tweaksRepo.autoConnectEnabled
                    .collectAsState(initial = true)
                LaunchedEffect(autoConnectEnabled) {
                    vm.setAutoConnectEnabled(autoConnectEnabled)
                }
                val tweaks = TweaksState(
                    motionReduced = false,
                    glassEnabled = false,
                    auroraOnLowBattery = false,
                    neonStrength = 1f,
                    autoConnectEnabled = autoConnectEnabled,
                )
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
                // Auto-connect retry ticker — runs ONLY while no
                // host is connected. This is the path that wakes a
                // freshly bonded Mac/PC up automatically without
                // requiring the user to tap CONNECT every time.
                //
                // Critically, we DO NOT poll while `status.host`
                // is non-null: an earlier revision unconditionally
                // ticked `refreshCompatibility` every 3 s, but that
                // function takes the gateway's `@Synchronized` lock
                // and makes a binder-bound `getConnectionState`
                // call inside it. Touchpad mouse reports go through
                // `BleHidGateway.send()`, which shares the same
                // lock — so during active cursor use the binder
                // probe stalled HID writes by 30–100 ms each tick
                // and the cursor visibly lagged "as if 2x packets
                // were being sent". Skipping the ticker when
                // connected lets `send()` keep the lock to itself.
                //
                // Silent disconnects while connected are caught by
                // `BluetoothDevice.ACTION_ACL_DISCONNECTED` (see
                // `BleHidGateway.ensureAclReceiverRegistered`) plus
                // the TrustCard manual `DISCONNECT` pill — neither
                // touches the lock or the main thread.
                val hostState by vm.status.collectAsState()
                LaunchedEffect(hostState.host == null) {
                    if (hostState.host != null) return@LaunchedEffect
                    while (true) {
                        delay(5000)
                        vm.refreshCompatibility()
                    }
                }
                // First-run gate. Welcome screen carries the
                // permission explainer + a CTA that flips the
                // persisted flag and triggers the actual permission
                // request — we deliberately do NOT fire the runtime
                // dialog before the user has read the rationale.
                val onboarded by tweaksRepo.onboarded.collectAsState(initial = true)
                if (!onboarded) {
                    WelcomeScreen(
                        onGetStarted = {
                            ioScope.launch { tweaksRepo.setOnboarded(true) }
                            requestBtPermissions()
                            maybeRequestNotificationsPermission()
                        },
                    )
                    return@BluetrackTheme
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
                        onButton = { label, pressed ->
                            // Dispatch:
                            //  - "HAT_n" labels → hat byte (0..7
                            //    direction, 8 = neutral release).
                            //  - Anything else → named button.
                            // Face buttons now report their real
                            // label on both press and release
                            // (Codex review on PR #55 caught the
                            // earlier `FACE_NONE` release path
                            // that left bits latched).
                            if (label.startsWith("HAT_")) {
                                val hat = if (pressed) {
                                    label.removePrefix("HAT_").toIntOrNull() ?: 8
                                } else {
                                    8
                                }
                                vm.processGamepadHat(hat)
                            } else {
                                vm.processGamepadButton(label, pressed)
                            }
                        },
                    )
                } else {
                    ScreenShell(
                        router = router,
                        motionReduced = tweaks.motionReduced,
                        glassEnabled = tweaks.glassEnabled,
                        neonStrength = tweaks.neonStrength,
                    ) { route ->
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
                                // Gateway has no per-id connect API yet — the
                                // CONNECT pill is a visual cue only; auto-
                                // connect picks the bonded computer.
                                onConnectHost = { /* TODO: connect by name */ },
                                // Unpair the bonded device by name via
                                // BluetoothDevice.removeBond() (reflection).
                                // Drops the TOFU host pin too if the removed
                                // device was the active host.
                                onDisconnectHost = { name -> vm.removeBondedDevice(name) },
                            )
                            Route.Activity -> ActivityScreen(
                                status = vm.status.collectAsState().value,
                                now = SystemClock.elapsedRealtime(),
                                onBack = { router.navigate(Route.Hub) },
                            )
                            Route.Diagnostics -> DiagnosticsScreen(
                                status = vm.status.collectAsState().value,
                                hidWave = vm.hidRateWindow.collectAsState().value,
                                fbWave = vm.feedbackRateWindow.collectAsState().value,
                            )
                            Route.Settings -> SettingsScreen(
                                status = vm.status.collectAsState().value,
                                versionName = BuildConfig.VERSION_NAME,
                                versionCode = BuildConfig.VERSION_CODE,
                                nearbyPermissionGranted = hasBluetoothPermissions(),
                                notificationsPermissionGranted = hasNotificationsPermission(),
                                autoConnectEnabled = autoConnectEnabled,
                                onAutoConnectChange = { persistAutoConnect(it) },
                                onOpenNotificationSettings = { openNotificationSettings() },
                                onOpenAppPermissions = { openAppPermissions() },
                                onOpenSourceCode = { openSourceCode() },
                                onResetLifetimeCounters = { vm.resetLifetimeCounters() },
                            )
                        }
                    }
                }
            }
        }
        // No unconditional permission request here — Welcome's
        // CTA fires `requestBtPermissions()` after the user has
        // read the rationale. Subsequent launches (where
        // `onboarded == true`) hit the runtime check via the
        // existing `bluetoothPermissions` / `enableBluetooth`
        // result handlers; `onResume` re-validates state.
        ioScope.launch {
            if (tweaksRepo.onboarded.firstOrNull() == true) {
                runOnUiThread { requestBtPermissions() }
            }
        }
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

    /**
     * Fire the `POST_NOTIFICATIONS` request on Android 13+. No-op
     * on older platforms (the permission did not exist) and when
     * already granted. Used by the Welcome CTA so the system
     * dialog appears right after the user reads the rationale.
     */
    private fun maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationsPermission()) return
        notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    /**
     * Enqueue the next [TweaksState] for atomic persistence.
     * Uses a conflated `MutableSharedFlow` (see [pendingTweaks])
     * so concurrent slider drags collapse into the latest value
     * and a single collector calls `tweaksRepo.setAll(...)` in
     * arrival order. No per-call coroutine, no per-key edit,
     * no race.
     */
    private fun persistTweaks(next: TweaksState) {
        pendingTweaks.tryEmit(next)
    }

    /**
     * Returns the current `POST_NOTIFICATIONS` grant state on
     * API 33+ where the runtime permission actually exists. Below
     * Tiramisu Android grants notifications automatically, so we
     * report `true`. The Settings route reads this via the
     * `notificationsPermissionGranted` argument; `null` would
     * mean "not yet plumbed" which no longer applies here.
     */
    private fun hasNotificationsPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Persist the auto-connect toggle. Uses the same conflated
     * pipeline as the legacy Appearance toggles so a rapid
     * tap-tap-tap cannot interleave stale writes.
     */
    private fun persistAutoConnect(enabled: Boolean) {
        val next = TweaksState(
            motionReduced = false,
            glassEnabled = false,
            auroraOnLowBattery = false,
            neonStrength = 1f,
            autoConnectEnabled = enabled,
        )
        pendingTweaks.tryEmit(next)
        // Propagate to the gateway immediately so the toggle
        // takes effect before the DataStore flow round-trip
        // completes.
        vm.setAutoConnectEnabled(enabled)
    }

    private fun openNotificationSettings() {
        // `ACTION_APP_NOTIFICATION_SETTINGS` lands on the per-app
        // channels page on API 26+; older devices fall through to
        // the generic app-details page via `openAppPermissions`.
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                appDetailsIntent()
            }
        startActivitySafely(intent, "Notification settings unavailable on this device.")
    }

    private fun openAppPermissions() {
        startActivitySafely(
            appDetailsIntent(),
            "App permissions screen unavailable on this device.",
        )
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openSourceCode() {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aiwaki/bluetrack"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivitySafely(intent, "No browser available to open the source repository.")
    }

    private fun startActivitySafely(intent: Intent, fallbackToast: String) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, fallbackToast, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, fallbackToast, Toast.LENGTH_SHORT).show()
        }
    }

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
                // Show bonded computer-class hosts as tappable
                // "recommended" rows so the user can wake the
                // Mac/PC from sleep without waiting for the
                // auto-connect tick.
                recommendedHosts = status.compatibility.hostKinds
                    .filterValues { it == BluetoothHostKind.Computer }
                    .keys
                    .sorted(),
                activeHost = status.host,
                onConnect = { name -> vm.connectHost(name) },
                onDisconnect = { vm.disconnectActiveHost() },
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
            Heartbeat(
                active = isConnected(status),
                // Real activity feed: how recently the last HID
                // report or input event landed. Full intensity for
                // the first 500 ms, linear decay to 0 over the next
                // 3 s — matches the eye's "is this still alive?"
                // window without flapping on individual frames.
                intensity = heartbeatIntensity(status, now),
            )
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
                        // Touchpad surface is portrait-shaped (taller
                        // than wide) on the Hub route, so the same
                        // physical finger swipe covers a smaller
                        // fraction of the X axis than of the Y axis.
                        // Equal-coefficient scaling (0.42f on both)
                        // mapped that into asymmetric cursor speed:
                        // X felt slower than Y. Normalise by the
                        // longer dimension so a swipe across 100 %
                        // of either axis emits the same magnitude.
                        //
                        // The per-axis clamp (`coerceIn`) ALSO has
                        // to scale — a uniform `-22..22` cap squashes
                        // the boosted X axis the moment a fast swipe
                        // pushes the scaled delta past 22. We lift
                        // each cap by the same factor so the linear
                        // response holds end-to-end.
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val longest = kotlin.math.max(w, h).coerceAtLeast(1f)
                        val sx = if (w > 0f) longest / w else 1f
                        val sy = if (h > 0f) longest / h else 1f
                        val capX = 22f * sx
                        val capY = 22f * sy

                        fun processPoint(
                            x: Float,
                            y: Float,
                        ) {
                            val dx = ((x - lastX) * sx * 0.42f).coerceIn(-capX, capX)
                            val dy = ((y - lastY) * sy * 0.42f).coerceIn(-capY, capY)
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

/**
 * Authoritative connection check. Earlier this OR'd against
 * `status.hid.contains("connected")` and
 * `status.pairing.contains("HID connected")`, but those strings
 * are sticky after `refreshCompatibility` clears `host` to null
 * (the stale-host probe resets `hid` to "HID ready" but the
 * pairing label can lag a tick), so the Hub kept reading
 * "ACTIVE LINK · MacBook Pro" minutes after Mac sleep / radio
 * off / quick suspend. The gateway is the single source of
 * truth for the host field — trust it.
 */
private fun isConnected(status: GatewayStatus): Boolean = status.host != null

private fun isInputLive(
    status: GatewayStatus,
    now: Long,
): Boolean = status.lastInputAtMs?.let { now - it < 1400L } == true

/**
 * Map the most recent input / HID-report timestamp into a 0..1
 * intensity the Heartbeat composable uses to scale spike height
 * and pulse rate. Hot in the first 500 ms after activity, linear
 * decay to 0 over the next 3 s.
 */
private fun heartbeatIntensity(
    status: GatewayStatus,
    now: Long,
): Float {
    val latest = listOfNotNull(status.lastInputAtMs, status.lastReportAtMs).maxOrNull() ?: return 0f
    val age = (now - latest).coerceAtLeast(0L)
    return when {
        age < 500L -> 1f
        age < 3_500L -> 1f - (age - 500L) / 3_000f
        else -> 0f
    }
}

private fun compactCount(value: Int): String =
    if (value < 1000) value.toString() else "${value / 1000}.${(value % 1000) / 100}k"
