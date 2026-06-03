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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xd.bluetrack.ble.BluetoothHostKind
import dev.xd.bluetrack.ble.GatewayStatus
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.ui.MainViewModel
import dev.xd.bluetrack.ui.Route
import dev.xd.bluetrack.ui.StickDeflection
import dev.xd.bluetrack.ui.TouchGestureClassifier
import dev.xd.bluetrack.ui.TouchpadSurfaceMode
import dev.xd.bluetrack.ui.activity.ActivityScreen
import dev.xd.bluetrack.ui.diag.DiagnosticsScreen
import dev.xd.bluetrack.ui.gamepad.GamepadSurface
import dev.xd.bluetrack.ui.gamepad.rememberFrameCounterState
import dev.xd.bluetrack.ui.hosts.HostsScreen
import dev.xd.bluetrack.ui.hub.ActivityStrip
import dev.xd.bluetrack.ui.hub.GamepadShortcut
import dev.xd.bluetrack.ui.hub.Heartbeat
import dev.xd.bluetrack.ui.hub.KeyboardShortcut
import dev.xd.bluetrack.ui.hub.ModeToggle
import dev.xd.bluetrack.ui.hub.MouseMirrorPanel
import dev.xd.bluetrack.ui.hub.NeonRibbon
import dev.xd.bluetrack.ui.hub.PinBlock
import dev.xd.bluetrack.ui.hub.StatusHero
import dev.xd.bluetrack.ui.hub.TouchpadHintsOverlay
import dev.xd.bluetrack.ui.hub.TrustCard
import dev.xd.bluetrack.ui.hub.TrustState
import dev.xd.bluetrack.ui.hub.toActivityItem
import dev.xd.bluetrack.ui.keyboard.KeyboardSurface
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
import kotlinx.coroutines.isActive
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
        // Ask Android to keep the window at the panel's highest
        // supported refresh rate. Without this hint the compositor
        // is free to downclock to 60 Hz when it decides the frame
        // budget allows it — animations and the cursor preview
        // visibly halve their cadence on 90/120/144 Hz devices.
        // `preferredDisplayModeId` is stronger than
        // `preferredRefreshRate` because it pins the actual mode
        // instead of asking the compositor to "try" honour the rate.
        // Picks the supported mode with the highest refresh rate at
        // the current resolution so we never force a resolution change.
        val targetDisplay = if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        targetDisplay?.let { d ->
            val activeMode = d.mode
            val best = d.supportedModes
                .filter {
                    it.physicalWidth == activeMode.physicalWidth &&
                        it.physicalHeight == activeMode.physicalHeight
                }.maxByOrNull { it.refreshRate }
            if (best != null && best.modeId != activeMode.modeId) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = best.modeId
                }
            }
        }
        val container = (application as BluetrackApplication).container
        vm = MainViewModel(container.bleGateway, container.translationEngine)
        tweaksRepo = TweaksRepository(applicationContext)
        // Preload the theme mode synchronously so the first frame
        // already paints in the right palette. Without this the
        // StateFlow's `initial = "SYSTEM"` could flash the wrong
        // palette for ~150 ms before the DataStore reader emitted
        // the persisted value, visible as a black ↔ white flip.
        // Drain pending tweaks on a single coroutine so writes
        // happen in arrival order and never race.
        ioScope.launch {
            pendingTweaks.collect { state -> tweaksRepo.setAll(state) }
        }
        setContent {
            // Follow the system theme unconditionally — the manual
            // Light / Dark / System selector was removed for a simpler,
            // unambiguous experience.
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            // Flip the system bar icon palette to match the
            // active theme. Light bg → dark icons, dark bg →
            // light icons. Re-applies `enableEdgeToEdge` with the
            // matching `SystemBarStyle` whenever `darkTheme`
            // changes; cheap because it just updates a couple of
            // window flags.
            androidx.compose.runtime.LaunchedEffect(darkTheme) {
                if (darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT,
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT,
                        ),
                    )
                }
            }
            BluetrackTheme(darkTheme = darkTheme) {
                val router = rememberRouter()
                var gamepadActive by remember { mutableStateOf(false) }
                var keyboardActive by remember { mutableStateOf(false) }
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
                val touchpadSensitivity by tweaksRepo.touchpadSensitivity
                    .collectAsState(initial = 1f)
                val touchpadHintsDismissed by tweaksRepo.touchpadHintsDismissed
                    .collectAsState(initial = true)
                val tweaks = TweaksState(
                    motionReduced = false,
                    glassEnabled = false,
                    auroraOnLowBattery = false,
                    neonStrength = 1f,
                    autoConnectEnabled = autoConnectEnabled,
                    touchpadSensitivity = touchpadSensitivity,
                )
                // Lock orientation to landscape while the gamepad
                // surface is up; restore to sensor when we leave.
                // `SideEffect` runs in the same frame as composition
                // so the rotation request fires before the gamepad
                // body draws — earlier `LaunchedEffect` deferred the
                // call by one frame, which painted the gamepad in
                // portrait first and then flipped, reading as a
                // visible orientation glitch.
                androidx.compose.runtime.SideEffect {
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
                        onFinish = {
                            ioScope.launch { tweaksRepo.setOnboarded(true) }
                        },
                        onRequestPermissions = {
                            requestBtPermissions()
                            maybeRequestNotificationsPermission()
                        },
                        nearbyPermissionGranted = hasBluetoothPermissions(),
                        notificationsPermissionGranted = hasNotificationsPermission(),
                    )
                    return@BluetrackTheme
                }
                if (keyboardActive) {
                    val kbStatus = vm.status.collectAsState().value
                    KeyboardSurface(
                        hostName = kbStatus.host ?: "Bluetrack",
                        onExit = { keyboardActive = false },
                        onKey = { mod, code -> vm.keyboardTap(mod, code) },
                    )
                    return@BluetrackTheme
                }
                if (gamepadActive) {
                    // Hide the gamepad body until the activity has
                    // actually rotated into landscape. Without this
                    // gate Compose paints the surface once in
                    // portrait, then the system rotation fires and
                    // we re-paint in landscape — visible as a
                    // glitchy flip. The placeholder fills the same
                    // bg0 so the transition reads as a smooth fade
                    // instead.
                    val config = androidx.compose.ui.platform.LocalConfiguration.current
                    val isLandscape = config.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    if (!isLandscape) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(dev.xd.bluetrack.ui.theme.BluetrackTheme.palette.bg0),
                        )
                        return@BluetrackTheme
                    }
                    val frame = rememberFrameCounterState()
                    val gamepadStatus = vm.status.collectAsState().value
                    val hidWaveForPad = vm.hidRateWindow.collectAsState().value
                    val sessionStartMs = remember { SystemClock.elapsedRealtime() }
                    var nowForPad by remember {
                        mutableLongStateOf(SystemClock.elapsedRealtime())
                    }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(1_000)
                            nowForPad = SystemClock.elapsedRealtime()
                        }
                    }
                    val pollHz = hidWaveForPad.lastOrNull()?.toInt() ?: 0
                    val latency = gamepadStatus.lastReportAtMs?.let {
                        ((nowForPad - it).coerceAtLeast(0L)).toFloat()
                    } ?: 0f
                    GamepadSurface(
                        hostName = gamepadStatus.host ?: "Bluetrack",
                        seq = frame.seq,
                        pulse = frame.pulse,
                        onExit = {
                            gamepadActive = false
                            vm.toggle(false)
                        },
                        pollHz = pollHz,
                        latencyMs = latency,
                        reportsTotal = gamepadStatus.lifetimeCounters.reports,
                        uptimeMs = (nowForPad - sessionStartMs).coerceAtLeast(0L),
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
                    val shellStatus by vm.status.collectAsState()
                    val auroraState =
                        when (router.current) {
                            Route.Diagnostics -> dev.xd.bluetrack.ui.shell.AuroraState.Diagnostics
                            Route.Hosts -> dev.xd.bluetrack.ui.shell.AuroraState.Hosts
                            Route.Activity -> dev.xd.bluetrack.ui.shell.AuroraState.Activity
                            Route.Settings -> dev.xd.bluetrack.ui.shell.AuroraState.Settings
                            Route.Hub ->
                                if (shellStatus.host != null) {
                                    dev.xd.bluetrack.ui.shell.AuroraState.Live
                                } else {
                                    dev.xd.bluetrack.ui.shell.AuroraState.Calm
                                }
                        }
                    ScreenShell(
                        router = router,
                        motionReduced = tweaks.motionReduced,
                        glassEnabled = tweaks.glassEnabled,
                        neonStrength = tweaks.neonStrength,
                        auroraState = auroraState,
                        darkTheme = darkTheme,
                    ) { route ->
                        when (route) {
                            Route.Hub -> AppScreen(
                                vm = vm,
                                touchpadSensitivity = touchpadSensitivity,
                                touchpadHintsVisible = !touchpadHintsDismissed,
                                onDismissTouchpadHints = {
                                    ioScope.launch { tweaksRepo.setTouchpadHintsDismissed(true) }
                                },
                                onNavigate = router::navigate,
                                onEnterGamepad = {
                                    vm.toggle(true)
                                    gamepadActive = true
                                },
                                onEnterKeyboard = { keyboardActive = true },
                                onShowTrustQR = { showTrustFingerprintToast() },
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
                                touchpadSensitivity = touchpadSensitivity,
                                onTouchpadSensitivityChange = { persistTouchpadSensitivity(it) },
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
     * Persist the theme mode (`SYSTEM` / `LIGHT` / `DARK`).
     * Direct `ds.edit { … }` write — the change is rare enough
     * that the conflated `pendingTweaks` pipeline is overkill.
     */
    private fun persistThemeMode(mode: String) {
        ioScope.launch { tweaksRepo.setThemeMode(mode) }
    }

    /**
     * Direct write — the slider can fire ~30 Hz during drag but
     * DataStore coalesces in-flight writes and this key is not
     * coupled to the rest of the TweaksState bundle.
     */
    private fun persistTouchpadSensitivity(value: Float) {
        ioScope.launch { tweaksRepo.setTouchpadSensitivity(value) }
    }

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

    /**
     * Placeholder until the identity-QR sheet lands (`--export-
     * identity` CLI). Surfaces the TOFU fingerprint via toast so
     * the Hub TrustCard "SHOW QR" button is not a dead tap.
     */
    private fun showTrustFingerprintToast() {
        val fp = vm.status.value.trustedHostFingerprint
        val msg = if (fp != null) {
            "Identity QR sheet is on the way. Trust pin: $fp"
        } else {
            "No host pinned yet — feedback channel must open first."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
    touchpadSensitivity: Float = 1f,
    touchpadHintsVisible: Boolean = false,
    onDismissTouchpadHints: () -> Unit = {},
    onNavigate: (Route) -> Unit = {},
    onEnterGamepad: () -> Unit = {},
    onEnterKeyboard: () -> Unit = {},
    onShowTrustQR: () -> Unit = {},
) {
    val mode by vm.mode.collectAsState()
    val surfaceMode by vm.surfaceMode.collectAsState()
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 100.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Step 3a: canvas Hub header (`[Blue·track]` wordmark + 26 sp
        // title + FG-service chip). The neon ribbon above flashes once
        // each time a fresh feedback PIN is issued — equivalent to the
        // canvas `NeonRibbon` keyed on a new GATT session.
        NeonRibbon(trigger = status.feedbackPin)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 0),
            ) {
                StatusHero(
                    connected = isConnected(status),
                    hostName = status.host,
                    // The live report COUNT lives in the ActivityStrip
                    // REPORTS cell (lifetime total); the hero only states
                    // status here, surfacing dropped feedback packets
                    // only when there are any — so the two no longer
                    // show competing HID counters.
                    metric = if (isConnected(status)) {
                        if (status.rejectedFeedbackPackets > 0) {
                            "HID active · ${status.rejectedFeedbackPackets} dropped"
                        } else {
                            "HID active"
                        }
                    } else {
                        null
                    },
                )
            }
            // ConnectionPanel (State / Host / Input / Flow + error)
            // moved to the Diagnostics route. Hub keeps the at-a-
            // glance hero + TrustCard; raw transport rows belong
            // with the rest of the diagnostic plumbing.
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 1),
            ) {
                PinBlock(
                    pin = status.feedbackPin,
                    session = sessionCount,
                    gattOpen = status.feedbackPin != null,
                )
            }
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 2),
            ) {
                TrustCard(
                    state = trustState,
                    fingerprint = status.trustedHostFingerprint,
                    onForget = { vm.forgetTrustedHost() },
                    onShowQR = onShowTrustQR,
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
            }
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 3),
            ) {
                ModeToggle(
                    surfaceMode = surfaceMode,
                    onToggle = { next -> vm.setSurfaceMode(next) },
                )
            }
            // Reports + Feedback `MetricTile` row moved to the
            // Diagnostics LiveRateHero (which already shows the
            // lifetime totals next to the peak-rate sparklines).
            //
            // Surface picker. Both branches render at a fixed 260dp
            // so the Hub layout below the toggle stays put across
            // the flip (no scroll jump when the user swaps modes).
            when (surfaceMode) {
                TouchpadSurfaceMode.TOUCHPAD -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .then(
                                dev.xd.bluetrack.ui
                                    .rememberStaggerModifier(index = 4),
                            ),
                    ) {
                        TouchpadPanel(
                            modifier = Modifier.fillMaxSize(),
                            mode = mode,
                            telemetryX = telemetry.stickX,
                            telemetryY = telemetry.stickY,
                            sensitivity = touchpadSensitivity,
                            onTouchStart = { vm.beginTouchGesture() },
                            onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
                            onScroll = { wheelDy, wheelDx -> vm.processScroll(wheelDy, wheelDx) },
                            onTapKey = { modifier, keycode -> vm.tapHidKey(modifier, keycode) },
                            onClick = { mask -> vm.processMouseClick(mask) },
                            onHoldStart = { vm.processMouseButton(1, true) },
                            onHoldEnd = { vm.processMouseButton(1, false) },
                        )
                        TouchpadHintsOverlay(
                            visible = touchpadHintsVisible,
                            onDismiss = onDismissTouchpadHints,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                TouchpadSurfaceMode.MOUSE -> {
                    Box(
                        modifier = dev.xd.bluetrack.ui
                            .rememberStaggerModifier(index = 4),
                    ) {
                        MouseMirrorPanel(
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            onMotion = { dx, dy, source -> vm.processMotion(dx, dy, source) },
                            onScroll = { wheelDy -> vm.processScroll(wheelDy) },
                            onButton = { mask, pressed -> vm.processMouseButton(mask, pressed) },
                        )
                    }
                }
            }
            // Stat triplet below the touchpad — mirrors the v2.4
            // reference's `REPORTS · LATENCY · UPTIME` row. Pulls
            // from the persisted lifetime counters + most-recent
            // report timestamp; UPTIME ticks from a session-start
            // anchor captured on first composition.
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 5),
            ) {
                HubStatRow(status = status, now = now)
            }
            // SystemPanel (BT / HID / Pair / BLE) moved to the
            // Diagnostics route alongside Connection.
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 6),
            ) {
                GamepadShortcut(onEnter = onEnterGamepad)
            }
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 6),
            ) {
                KeyboardShortcut(onEnter = onEnterKeyboard)
            }
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 7),
            ) {
                ActivityStrip(
                    items = status.events.take(4).map { it.toActivityItem(relativeAgeLabel(now, it.timestampMs)) },
                    onOpen = { onNavigate(Route.Activity) },
                )
            }
            Box(
                modifier = dev.xd.bluetrack.ui
                    .rememberStaggerModifier(index = 8),
            ) {
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
}

@Composable
private fun TouchpadPanel(
    modifier: Modifier,
    mode: HidMode,
    telemetryX: Int,
    telemetryY: Int,
    onTouchStart: () -> Unit,
    onMotion: (Float, Float, String) -> Unit,
    /**
     * Two-axis wheel. `(wheelDy, wheelDx)` — vertical first (the
     * existing kinetic-scroll path), horizontal second (AC Pan,
     * the 5th mouse byte). Horizontal is 0f for a pure vertical
     * scroll so the vertical feel is unchanged.
     */
    onScroll: (Float, Float) -> Unit = { _, _ -> },
    /**
     * Mac-trackpad gesture → keyboard chord. `(modifier, keycode)`
     * with `modifier` an OR of `HidKeys.MOD_*` (0 = none) and
     * `keycode` a HID Usage page 0x07 code. Fires once per pinch
     * notch / swipe; routed to keyboard report ID 3, mouse mode
     * only (gated in the ViewModel).
     */
    onTapKey: (Int, Int) -> Unit = { _, _ -> },
    /**
     * Mouse-button click from a tap gesture. `mask` matches HID
     * button bits (1 = left, 2 = right). The handler fires a
     * momentary press → release on the gateway; held drags do
     * NOT come through this callback (motion is enough).
     */
    onClick: (Int) -> Unit = {},
    /**
     * Long-press hold start. Fires after ~350ms with the finger
     * stationary on the touchpad — emit a sustained L-press so
     * subsequent finger motion drags with the button held (text
     * selection). Released via [onHoldEnd] on finger lift.
     */
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
    sensitivity: Float = 1f,
) {
    val gestureScope = rememberCoroutineScope()
    val touchpadHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The AndroidView's `setOnTouchListener` is installed once at
    // factory time, so it captures the Kotlin parameter `sensitivity`
    // only once. Wrap in `rememberUpdatedState` so the closure
    // re-reads the latest slider value on every event without
    // rebuilding the FrameLayout.
    val sensitivityState = rememberUpdatedState(sensitivity)
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
    // Touchpad surface intentionally renders NOTHING under the
    // finger in mouse mode. A real Mac trackpad shows nothing on
    // the pad itself — the cursor on the host is the only
    // feedback. The earlier radial-drop indicator (and before
    // that, the polyline trail) read as decorative debt and let
    // the surface compete visually with the host cursor.
    // Gamepad mode keeps its stick-well overlay because the
    // surface IS the virtual stick there; the absence here is
    // mode-specific.
    // Mouse mode: clean bordered zone with no center crosshair
    // and no edge labels. The user wants a hardware-trackpad feel
    // — gesture hints come from a future onboarding overlay, not
    // permanent on-surface text.
    // Gamepad mode: keep the stick well visualization (radial
    // grid + travel rings + telemetry dot) because that surface
    // doubles as a virtual analog stick.
    val touchpadModifier =
        if (!isGamepad) {
            modifier.then(
                Modifier.border(
                    width = 1.dp,
                    color = palette.hairline,
                    shape = RoundedCornerShape(8.dp),
                ),
            )
        } else {
            modifier
        }
    Panel(touchpadModifier) {
        Box(Modifier.fillMaxSize()) {
            if (isGamepad) {
                Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val baseRadius = minOf(size.width, size.height) * 0.27f
                    val travelRadius = minOf(size.width, size.height) * 0.42f
                    drawCircle(Color(0x2400E5FF), baseRadius, Offset(cx, cy))
                    drawLine(
                        Color(0xFF00E5FF),
                        Offset(cx - baseRadius * 1.3f, cy),
                        Offset(cx + baseRadius * 1.3f, cy),
                        2f,
                    )
                    drawLine(
                        Color(0xFF00E5FF),
                        Offset(cx, cy - baseRadius * 1.3f),
                        Offset(cx, cy + baseRadius * 1.3f),
                        2f,
                    )
                    val ringColor = Color.White.copy(alpha = 0.22f)
                    drawCircle(ringColor, travelRadius, Offset(cx, cy), style = Stroke(width = 2f))
                    drawCircle(ringColor, baseRadius * 0.4f, Offset(cx, cy), style = Stroke(width = 1.5f))
                    val dotOffset = Offset(cx + stick.normalizedX * travelRadius, cy + stick.normalizedY * travelRadius)
                    drawCircle(dotColor, 14f, dotOffset)
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
                    // Two-finger scroll state. `lastScrollY` is
                    // the average Y of the two pointers on the
                    // previous frame; `inScrollGesture` latches
                    // for the lifetime of a gesture so a brief
                    // 1-finger interlude (the user momentarily
                    // lifting one finger) does not flip back to
                    // motion and lurch the cursor.
                    var lastScrollY = 0f
                    var inScrollGesture = false
                    // Kinetic scroll (Mac-style fling). Tracked
                    // during 2-finger drag, replayed on release
                    // as exponentially-decaying wheel emissions
                    // so a quick flick keeps the page coasting
                    // for a second or two and a slow drag stops
                    // immediately. A new ACTION_DOWN cancels the
                    // in-flight fling so the user can stop the
                    // momentum by re-touching the surface — same
                    // gesture as a hardware Mac trackpad.
                    var lastMoveTimeMs = 0L
                    var scrollVelocityPxPerMs = 0f
                    var scrollFlingJob: kotlinx.coroutines.Job? = null
                    // Velocity-driven active scroll. The earlier
                    // per-MOVE direct onScroll(-dyPx/42f) emitted
                    // wheel deltas at whatever rate Android
                    // delivered touch events — typically bursty,
                    // 1-2 events per 8 ms with chunky dyPx
                    // values. Hosts read that as jerky scroll.
                    // Replaced by a constant 8 ms tick that
                    // emits proportional ticks from the EMA-
                    // smoothed velocity, so finger speed maps
                    // directly to scroll rate. Identical model
                    // to the fling job, just driven by live
                    // MOVE samples instead of decaying.
                    var scrollActiveTickJob: kotlinx.coroutines.Job? = null
                    var lastVelocitySampleAtMs = 0L
                    // Tracks how many fingers were down on the
                    // previous scroll-MOVE frame. When this
                    // changes (user lifts or adds a finger
                    // mid-gesture), `lastScrollY` no longer
                    // references the same set of pointers as the
                    // current `avgY()`, so the next dyPx becomes
                    // a phantom 50-100px jump in whichever
                    // direction the lifted finger biased the
                    // average — felt as jittery scroll or a
                    // wrong-direction fling on release. We
                    // re-anchor the baseline whenever the count
                    // changes and skip emit for that frame.
                    var lastScrollPointerCount = 0
                    // Tap-to-click state. ACTION_DOWN seeds the
                    // timer + counters; ACTION_UP commits the
                    // click iff the gesture stayed short in time
                    // and travel and never latched scroll. 1
                    // finger → left, 2+ fingers → right (max
                    // pointer count during the gesture).
                    var downAtMs = 0L
                    var maxPointers = 1
                    var totalMovementPx = 0f
                    val tapMaxDurationMs = 250L
                    val tapMaxTravelPx = 12f
                    // Long-press hold state. Finger held still
                    // for `holdDelayMs` triggers the host-side
                    // L-press latch via `onHoldStart`; subsequent
                    // motion drags with the button held (text
                    // select). Released on ACTION_UP via
                    // `onHoldEnd`. Cancelled if the finger moves
                    // past the tap-slop or a second finger lands
                    // (scroll / right-click intent).
                    var holdActive = false
                    var holdJob: kotlinx.coroutines.Job? = null
                    val holdDelayMs = 350L
                    // Horizontal-scroll (AC Pan) counterparts to the
                    // vertical kinetic-scroll state. `lastScrollX`
                    // mirrors `lastScrollY`; `scrollVelocityXPxPerMs`
                    // rides the same EMA + fling so two-finger
                    // diagonal scroll feels uniform across axes.
                    var lastScrollX = 0f
                    var scrollVelocityXPxPerMs = 0f
                    // 3-/4-finger swipe + pinch. `gestureKeyLatched`
                    // makes the chord one-shot — once a swipe fires
                    // we swallow the rest of the gesture so a single
                    // physical swipe never repeats. `threeStart*` and
                    // `fourStartSpread` anchor the gesture origin.
                    var gestureKeyLatched = false
                    var threeStartX = Float.NaN
                    var threeStartY = Float.NaN
                    var fourStartSpread = -1f
                    // Palm-slap reject. `sawThreePointers` records the
                    // 2→3→4 ramp; `lastPointerCount` carries the prior
                    // event's count so a <2→≥4 jump that skips 3 inside
                    // PALM_SLAP_WINDOW_MS reads as a palm and suppresses
                    // every emit for the gesture.
                    var sawThreePointers = false
                    var lastPointerCount = 0
                    var palmRejected = false
                    // dp gesture tunables → px at the live display
                    // density. Computed once at factory time.
                    val gestureDensity = resources.displayMetrics.density
                    val swipeThresholdPx = TouchGestureClassifier.SWIPE_THRESHOLD_DP * gestureDensity
                    val fourFingerNotchPx = TouchGestureClassifier.FOUR_FINGER_NOTCH_DP * gestureDensity
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
                        // Axis scaling history:
                        //   v1: equal `0.42f` on both → X felt slow
                        //       because portrait card had less X
                        //       travel.
                        //   v2: normalise by the longest dimension
                        //       (sx = longest/w, sy = longest/h).
                        //       Worked when the touchpad card was
                        //       portrait; the Hub card is now
                        //       LANDSCAPE (260dp tall, fillMaxWidth)
                        //       so the same normalise boosted Y
                        //       instead, making Y feel too fast.
                        //   v3 (here): keep gain flat per-axis and
                        //       rely on the acceleration + edge
                        //       boost below to deliver reach.
                        //       Cursor speed per finger-mm is now
                        //       symmetric regardless of card
                        //       aspect.
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val sx = 1f
                        val sy = 1f
                        val capX = 22f
                        val capY = 22f

                        // Acceleration + edge boost. The
                        // touchpad surface is bounded by the phone
                        // screen, so a single finger swipe can only
                        // cover ~h pixels vertically and ~w pixels
                        // horizontally. A linear `0.42` gain forces
                        // the user to re-grip mid-stroke to cross a
                        // wide host display. Two compensations:
                        //
                        //  1. Velocity curve. Slow finger keeps the
                        //     base gain for precision. Fast flick
                        //     scales up to ~2.8× so a single throw
                        //     can carry the cursor a full host
                        //     screen across.
                        //  2. Edge zone (~12 % of the shorter side).
                        //     A finger that runs out of trackpad
                        //     room gets an extra ×1..2.2 multiplier
                        //     so the cursor still finishes the
                        //     gesture instead of stalling at the
                        //     rim.
                        //
                        // Caps lift by the combined max boost so
                        // accelerated samples are not clipped back
                        // to the linear ceiling.
                        val edgeBand = (kotlin.math.min(w, h) * 0.12f).coerceAtLeast(1f)
                        val accelRefSpeedPx = 28f
                        val accelMax = 1.8f
                        val edgeMax = 1.2f
                        val maxBoost = (1f + accelMax) * (1f + edgeMax)
                        val capXBoosted = capX * maxBoost
                        val capYBoosted = capY * maxBoost

                        fun processPoint(
                            x: Float,
                            y: Float,
                        ) {
                            val rawDx = x - lastX
                            val rawDy = y - lastY
                            lastX = x
                            lastY = y
                            val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
                            val accelT = kotlin.math.min(speed / accelRefSpeedPx, 1f)
                            val accel = 1f + accelT * accelT * accelMax
                            // Sensitivity slider scales the
                            // baseline gain before acceleration.
                            // Read via the captured State so a
                            // mid-gesture drag of the slider takes
                            // effect on the next sample without
                            // rebuilding the touch listener.
                            val gain = 0.42f * accel * sensitivityState.value
                            val edgeBoostX =
                                1f +
                                    when {
                                        x < edgeBand -> (1f - (x / edgeBand).coerceIn(0f, 1f)) * edgeMax
                                        x > w - edgeBand -> (1f - ((w - x) / edgeBand).coerceIn(0f, 1f)) * edgeMax
                                        else -> 0f
                                    }
                            val edgeBoostY =
                                1f +
                                    when {
                                        y < edgeBand -> (1f - (y / edgeBand).coerceIn(0f, 1f)) * edgeMax
                                        y > h - edgeBand -> (1f - ((h - y) / edgeBand).coerceIn(0f, 1f)) * edgeMax
                                        else -> 0f
                                    }
                            val dx = (rawDx * sx * gain * edgeBoostX).coerceIn(-capXBoosted, capXBoosted)
                            val dy = (rawDy * sy * gain * edgeBoostY).coerceIn(-capYBoosted, capYBoosted)
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

                        // Pre-compute a "did the finger leave the
                        // touchpad zone" check. Android keeps
                        // delivering MOVE events past the View's
                        // bounds while a gesture is captured
                        // (`requestDisallowInterceptTouchEvent`),
                        // which a hardware trackpad does NOT — once
                        // your finger slides off the trackpad, input
                        // stops. Mirror that: gate emit on the
                        // primary pointer being inside the View.
                        val primaryInside = ev.x in 0f..w && ev.y in 0f..h

                        // Average Y of the first two pointers.
                        // Used as the scroll-gesture displacement
                        // reference so a tilt of the hand (one
                        // finger moves slightly more than the
                        // other) does not jitter the scroll axis.
                        fun avgY(): Float = if (ev.pointerCount >= 2) (ev.getY(0) + ev.getY(1)) * 0.5f else ev.y

                        // Average X of the first two pointers — the
                        // horizontal-scroll counterpart to avgY().
                        fun avgX(): Float = if (ev.pointerCount >= 2) (ev.getX(0) + ev.getX(1)) * 0.5f else ev.x

                        // Centroid of all current pointers — the
                        // displacement reference for 3-finger swipes
                        // (robust to one finger drifting).
                        fun centroidX(): Float {
                            val n = ev.pointerCount
                            if (n == 0) return ev.x
                            var s = 0f
                            for (i in 0 until n) s += ev.getX(i)
                            return s / n
                        }

                        fun centroidY(): Float {
                            val n = ev.pointerCount
                            if (n == 0) return ev.y
                            var s = 0f
                            for (i in 0 until n) s += ev.getY(i)
                            return s / n
                        }

                        // Mean distance of all pointers from their
                        // centroid — the 4-finger pinch/spread metric.
                        fun spread(): Float {
                            val n = ev.pointerCount
                            if (n < 2) return 0f
                            val cx = centroidX()
                            val cy = centroidY()
                            var s = 0f
                            for (i in 0 until n) {
                                val dx = ev.getX(i) - cx
                                val dy = ev.getY(i) - cy
                                s += kotlin.math.sqrt(dx * dx + dy * dy)
                            }
                            return s / n
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
                                inScrollGesture = false
                                downAtMs = ev.eventTime
                                maxPointers = 1
                                totalMovementPx = 0f
                                holdActive = false
                                holdJob?.cancel()
                                // Touch-to-stop the kinetic
                                // scroll fling. Putting a finger
                                // down on a coasting page halts
                                // the momentum immediately so
                                // the user can land precisely.
                                scrollFlingJob?.cancel()
                                scrollActiveTickJob?.cancel()
                                scrollVelocityPxPerMs = 0f
                                scrollVelocityXPxPerMs = 0f
                                lastScrollX = 0f
                                lastMoveTimeMs = ev.eventTime
                                lastScrollPointerCount = 0
                                // Multi-finger gesture state — fresh
                                // every gesture so a previous swipe's
                                // latch never bleeds into the next.
                                gestureKeyLatched = false
                                threeStartX = Float.NaN
                                threeStartY = Float.NaN
                                fourStartSpread = -1f
                                sawThreePointers = false
                                palmRejected = false
                                lastPointerCount = 1
                                holdJob = gestureScope.launch {
                                    kotlinx.coroutines.delay(holdDelayMs)
                                    // Only commit hold if the
                                    // finger has stayed still and
                                    // a second finger has not
                                    // landed. The flag is checked
                                    // again on each subsequent
                                    // ACTION_MOVE to short-circuit
                                    // if travel exceeds slop
                                    // after this fires (rare race
                                    // — Android usually delivers
                                    // MOVE events more frequently
                                    // than 350 ms apart).
                                    if (totalMovementPx < tapMaxTravelPx && maxPointers == 1) {
                                        holdActive = true
                                        touchpadHaptic.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                        )
                                        onHoldStart()
                                    }
                                }
                                true
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                // Second finger landed → seed
                                // the scroll baseline so the
                                // first MOVE delta after the
                                // pointer-down is zero, but DO
                                // NOT latch scroll yet. Latching
                                // here would make a quick 2-
                                // finger tap (down + up without
                                // moving) read as scroll and
                                // never as a right-click. Scroll
                                // latches on the first 2-finger
                                // MOVE instead.
                                if (ev.pointerCount > maxPointers) maxPointers = ev.pointerCount
                                // Track the 2→3→4 ramp. A genuine
                                // multi-finger gesture passes through
                                // a 3-pointer frame; a flat palm jumps
                                // straight from <2 to ≥4.
                                if (ev.pointerCount == 3) sawThreePointers = true
                                if (TouchGestureClassifier.isPalmSlap(
                                        prevPointerCount = lastPointerCount,
                                        newPointerCount = ev.pointerCount,
                                        sawThreePointers = sawThreePointers,
                                        elapsedMs = ev.eventTime - downAtMs,
                                    )
                                ) {
                                    palmRejected = true
                                }
                                lastPointerCount = ev.pointerCount
                                if (ev.pointerCount >= 2) {
                                    lastScrollY = avgY()
                                    lastScrollX = avgX()
                                    // Reset velocity baseline so
                                    // the first 2-finger MOVE
                                    // delta does not pick up a
                                    // stale instant velocity
                                    // from before the second
                                    // finger landed.
                                    scrollVelocityPxPerMs = 0f
                                    scrollVelocityXPxPerMs = 0f
                                    lastMoveTimeMs = ev.eventTime
                                    // A second finger means the
                                    // gesture is heading for
                                    // scroll or right-click, not
                                    // a hold. Cancel the pending
                                    // hold timer.
                                    if (!holdActive) holdJob?.cancel()
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                // Track travel for the tap detector
                                // regardless of which branch we
                                // take. Use raw move delta from
                                // the last sample so a long slow
                                // drag still disqualifies a tap.
                                val moveDx = ev.x - lastX
                                val moveDy = ev.y - lastY
                                totalMovementPx += kotlin.math.sqrt(moveDx * moveDx + moveDy * moveDy)
                                // If the user starts dragging
                                // BEFORE the hold timer fires,
                                // cancel it — they intended a
                                // motion gesture, not a hold.
                                // Drag while hold is already
                                // active is the desired path
                                // (text select), so do nothing.
                                if (!holdActive && totalMovementPx > tapMaxTravelPx) {
                                    holdJob?.cancel()
                                }
                                // Palm slap: swallow the whole
                                // gesture so a flat hand never emits
                                // scroll, cursor, or a chord.
                                if (palmRejected) return@setOnTouchListener true
                                // One chord already fired this gesture
                                // (3-/4-finger). Swallow the tail so a
                                // single swipe never repeats.
                                if (gestureKeyLatched) return@setOnTouchListener true

                                // Helper: a multi-finger gesture is
                                // never scroll/cursor — drop any scroll
                                // latch a transient 2-finger frame
                                // started before the 3rd/4th finger
                                // landed.
                                fun dropScrollLatch() {
                                    if (inScrollGesture) {
                                        scrollActiveTickJob?.cancel()
                                        inScrollGesture = false
                                        scrollVelocityPxPerMs = 0f
                                        scrollVelocityXPxPerMs = 0f
                                    }
                                }

                                // --- 4-finger pinch / spread → F4 / F11
                                // (one-shot). maxPointers latches the
                                // family for the gesture's lifetime so
                                // fingers lifting at the end do not fall
                                // back into a 3-finger or scroll path.
                                if (maxPointers >= 4) {
                                    dropScrollLatch()
                                    if (ev.pointerCount >= 4) {
                                        if (fourStartSpread < 0f) fourStartSpread = spread()
                                        val chord = TouchGestureClassifier.fourFingerZoom(
                                            spread() - fourStartSpread,
                                            fourFingerNotchPx,
                                        )
                                        if (chord != null) {
                                            onTapKey(chord.modifier, chord.keycode)
                                            gestureKeyLatched = true
                                        }
                                    }
                                    return@setOnTouchListener true
                                }
                                // --- 3-finger swipe → desktop chords
                                // (one-shot, dominant axis).
                                if (maxPointers == 3) {
                                    dropScrollLatch()
                                    if (ev.pointerCount >= 3) {
                                        if (threeStartX.isNaN()) {
                                            threeStartX = centroidX()
                                            threeStartY = centroidY()
                                        }
                                        val chord = TouchGestureClassifier.threeFingerSwipe(
                                            centroidX() - threeStartX,
                                            centroidY() - threeStartY,
                                            swipeThresholdPx,
                                        )
                                        if (chord != null) {
                                            onTapKey(chord.modifier, chord.keycode)
                                            gestureKeyLatched = true
                                        }
                                    }
                                    return@setOnTouchListener true
                                }

                                // Lazy scroll latch: only on the first
                                // 2-finger MOVE with real travel, so a
                                // quick 2-finger down + up without
                                // motion still registers as a
                                // right-click tap.
                                if (!inScrollGesture && ev.pointerCount >= 2 && totalMovementPx > 6f) {
                                    inScrollGesture = true
                                    lastScrollY = avgY()
                                    lastScrollX = avgX()
                                    lastScrollPointerCount = ev.pointerCount
                                    // Start active-scroll ticker
                                    // so subsequent ticks emit at
                                    // constant 8 ms rate from the
                                    // smoothed velocity. Cancels
                                    // any leftover ticker first
                                    // (paranoia — should never be
                                    // alive at this point because
                                    // ACTION_DOWN cancels).
                                    scrollActiveTickJob?.cancel()
                                    lastVelocitySampleAtMs = ev.eventTime
                                    scrollActiveTickJob = gestureScope.launch {
                                        val tickMs = 8L
                                        while (isActive) {
                                            kotlinx.coroutines.delay(tickMs)
                                            val now = SystemClock.elapsedRealtime()
                                            // Decay velocity when
                                            // no MOVE arrives for
                                            // a while — finger
                                            // sitting still must
                                            // stop scroll, not
                                            // coast. 30 ms gap is
                                            // ~4 ticks; long
                                            // enough to ride out
                                            // Android's MOVE
                                            // jitter, short enough
                                            // that a paused finger
                                            // feels responsive.
                                            if (now - lastVelocitySampleAtMs > 30L) {
                                                scrollVelocityPxPerMs *= 0.85f
                                                scrollVelocityXPxPerMs *= 0.85f
                                            }
                                            val vy = scrollVelocityPxPerMs
                                            val vx = scrollVelocityXPxPerMs
                                            if (kotlin.math.abs(vy) < 0.01f &&
                                                kotlin.math.abs(vx) < 0.01f
                                            ) {
                                                continue
                                            }
                                            val dy = vy * tickMs.toFloat()
                                            val dx = vx * tickMs.toFloat()
                                            // Vertical keeps its sign
                                            // (negated finger delta).
                                            // Horizontal AC Pan tracks
                                            // finger direction; the
                                            // tester confirms polarity
                                            // on the host (flip the dx
                                            // sign here if reversed).
                                            onScroll(-dy / 42f, dx / 42f)
                                        }
                                    }
                                }
                                if (inScrollGesture && ev.pointerCount != lastScrollPointerCount) {
                                    // Pointer config changed
                                    // (finger lifted or added).
                                    // Re-anchor without emitting
                                    // — `avgY()` now references a
                                    // different set, so the
                                    // would-be `dyPx` against
                                    // the old baseline is noise.
                                    lastScrollY = avgY()
                                    lastScrollX = avgX()
                                    lastScrollPointerCount = ev.pointerCount
                                    scrollVelocityPxPerMs = 0f
                                    scrollVelocityXPxPerMs = 0f
                                    lastMoveTimeMs = ev.eventTime
                                    return@setOnTouchListener true
                                }
                                if (inScrollGesture) {
                                    val y = avgY()
                                    val dyPx = y - lastScrollY
                                    lastScrollY = y
                                    val x = avgX()
                                    val dxPx = x - lastScrollX
                                    lastScrollX = x
                                    // Track signed instantaneous
                                    // finger velocity in px/ms
                                    // with EMA smoothing. The
                                    // smoothing weight (0.4 new,
                                    // 0.6 old) tames jitter from
                                    // single-sample noise but
                                    // still responds inside one
                                    // gesture so a flick is read
                                    // as fast, not averaged
                                    // down. Carried into the
                                    // fling job on release.
                                    val nowMs = ev.eventTime
                                    val dtMs = (nowMs - lastMoveTimeMs).coerceAtLeast(1L)
                                    val instantV = dyPx / dtMs.toFloat()
                                    // Direction-aware velocity
                                    // tracker. If the user
                                    // reverses scroll direction
                                    // mid-gesture (sign flip in
                                    // instantV), discard the EMA
                                    // history and start fresh —
                                    // otherwise the smoothing
                                    // weight keeps the old
                                    // direction long enough that
                                    // the release-time fling
                                    // launches against the
                                    // user's last intent. New
                                    // weights favour the latest
                                    // sample (0.7 new, 0.3 old)
                                    // so even unidirectional
                                    // drags converge on real
                                    // velocity faster.
                                    scrollVelocityPxPerMs = when {
                                        instantV != 0f &&
                                            scrollVelocityPxPerMs != 0f &&
                                            instantV * scrollVelocityPxPerMs < 0f -> instantV
                                        else -> scrollVelocityPxPerMs * 0.3f + instantV * 0.7f
                                    }
                                    // Horizontal axis rides the same
                                    // direction-aware EMA so diagonal
                                    // two-finger scroll feels uniform.
                                    val instantVx = dxPx / dtMs.toFloat()
                                    scrollVelocityXPxPerMs = when {
                                        instantVx != 0f &&
                                            scrollVelocityXPxPerMs != 0f &&
                                            instantVx * scrollVelocityXPxPerMs < 0f -> instantVx
                                        else -> scrollVelocityXPxPerMs * 0.3f + instantVx * 0.7f
                                    }
                                    lastMoveTimeMs = nowMs
                                    lastVelocitySampleAtMs = SystemClock.elapsedRealtime()
                                    // Active-scroll ticker emits
                                    // wheel deltas based on this
                                    // updated velocity on its own
                                    // 8 ms cadence. No direct
                                    // onScroll call from MOVE so
                                    // bursty Android touch
                                    // delivery does not translate
                                    // into bursty wheel output.
                                    // Keep lastX/lastY current so
                                    // the tap-travel accumulator
                                    // doesn't fire on every frame
                                    // with stale anchors.
                                    lastX = ev.x
                                    lastY = ev.y
                                } else {
                                    if (primaryInside) {
                                        for (i in 0 until ev.historySize) {
                                            val hx = ev.getHistoricalX(i)
                                            val hy = ev.getHistoricalY(i)
                                            if (hx in 0f..w && hy in 0f..h) processPoint(hx, hy)
                                        }
                                        processPoint(ev.x, ev.y)
                                        emitBatch()
                                    } else {
                                        // Re-anchor so a finger that
                                        // wanders back into the zone
                                        // does not emit a phantom
                                        // jump-delta from the last
                                        // in-bounds sample.
                                        lastX = ev.x
                                        lastY = ev.y
                                    }
                                }
                                true
                            }
                            MotionEvent.ACTION_POINTER_UP -> {
                                // Drop from 2 → 1 fingers but keep
                                // `inScrollGesture` latched until
                                // ACTION_UP so the remaining finger
                                // does not snap back into a cursor
                                // drag mid-stroke.
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                parent.requestDisallowInterceptTouchEvent(false)
                                performClick()
                                // Hold-to-drag release. If the
                                // 350 ms long-press latched the
                                // L-button via `onHoldStart`,
                                // release it now so the host sees
                                // a clean drag boundary (button
                                // up, motion stops with whatever
                                // text selection / window drag
                                // the user built). Skip the tap
                                // detector — the gesture was a
                                // hold, not a tap.
                                holdJob?.cancel()
                                if (holdActive) {
                                    onHoldEnd()
                                    holdActive = false
                                } else if (
                                    ev.actionMasked == MotionEvent.ACTION_UP &&
                                    !inScrollGesture &&
                                    totalMovementPx < tapMaxTravelPx &&
                                    (ev.eventTime - downAtMs) < tapMaxDurationMs
                                ) {
                                    // Tap detector. Commits a
                                    // click iff the gesture
                                    // stayed short in time and
                                    // travel, never latched
                                    // scroll, and ended cleanly
                                    // (not cancelled). 1 finger →
                                    // left button (mask 1), 2+
                                    // fingers → right button
                                    // (mask 2). Held drags exit
                                    // through the motion path
                                    // and never reach this
                                    // branch.
                                    val mask = if (maxPointers >= 2) 2 else 1
                                    onClick(mask)
                                }
                                // Kinetic fling. If the gesture
                                // ended in scroll mode with
                                // enough finger velocity, launch
                                // a coroutine that keeps emitting
                                // scroll wheel deltas with an
                                // exponential decay so the page
                                // coasts. 0.94^n per 16ms tick
                                // gives ~1-1.5s of perceivable
                                // motion for a hard flick, and
                                // dies inside ~250ms for a slow
                                // drag — same feel as a Mac
                                // trackpad. ACTION_DOWN cancels
                                // the job (touch-to-stop).
                                // Stop the active-scroll ticker
                                // BEFORE evaluating fling so the
                                // ticker does not double-emit
                                // against the fling.
                                scrollActiveTickJob?.cancel()
                                if (inScrollGesture &&
                                    (
                                        kotlin.math.abs(scrollVelocityPxPerMs) > FLING_MIN_VELOCITY ||
                                            kotlin.math.abs(scrollVelocityXPxPerMs) > FLING_MIN_VELOCITY
                                    )
                                ) {
                                    val initialV = scrollVelocityPxPerMs
                                    val initialVx = scrollVelocityXPxPerMs
                                    scrollFlingJob?.cancel()
                                    scrollFlingJob = gestureScope.launch {
                                        // Damp the initial fling
                                        // velocity (60% of the
                                        // raw finger velocity) so
                                        // a casual flick does not
                                        // overshoot. Pair with a
                                        // tighter decay (0.92 per
                                        // 16ms) so total coast is
                                        // ~700ms — still feels
                                        // alive but the page
                                        // lands where the user
                                        // expects instead of
                                        // running on.
                                        var v = initialV * 0.6f
                                        var vx = initialVx * 0.6f
                                        // 8 ms tick at sqrt(0.92)
                                        // decay ≈ same total
                                        // energy curve as the
                                        // previous 16 ms / 0.92
                                        // version but each
                                        // emission is half the
                                        // magnitude and fires
                                        // twice as often. Host
                                        // sees a finer-grained
                                        // wheel stream that
                                        // reads visually as
                                        // smoother coast. Both
                                        // axes coast together so a
                                        // diagonal flick keeps its
                                        // angle.
                                        val tickMs = 8L
                                        while (kotlin.math.abs(v) > FLING_STOP_VELOCITY ||
                                            kotlin.math.abs(vx) > FLING_STOP_VELOCITY
                                        ) {
                                            kotlinx.coroutines.delay(tickMs)
                                            val flingDy = v * tickMs.toFloat()
                                            val flingDx = vx * tickMs.toFloat()
                                            onScroll(-flingDy / 42f, flingDx / 42f)
                                            v *= 0.959f
                                            vx *= 0.959f
                                        }
                                    }
                                }
                                scrollVelocityPxPerMs = 0f
                                scrollVelocityXPxPerMs = 0f
                                inScrollGesture = false
                                // Re-arm multi-finger detection for the
                                // next gesture (ACTION_DOWN also resets,
                                // but ACTION_CANCEL may skip a fresh
                                // DOWN, so clear here too).
                                gestureKeyLatched = false
                                threeStartX = Float.NaN
                                threeStartY = Float.NaN
                                fourStartSpread = -1f
                                sawThreePointers = false
                                palmRejected = false
                                lastPointerCount = 0
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

/**
 * Hub stat triplet — REPORTS · LATENCY · UPTIME. Lives below
 * the touchpad as the design reference's at-a-glance footer.
 * Values flow from `status.lifetimeCounters.reports`, the most
 * recent HID-report timestamp age, and a session-start anchor
 * captured the first time this composable runs.
 */
@Composable
private fun HubStatRow(status: GatewayStatus, now: Long) {
    val sessionStartMs = remember { SystemClock.elapsedRealtime() }
    val reportsLabel = compactCount(
        status.lifetimeCounters.reports
            .toInt()
            .coerceAtLeast(0),
    )
    val uptimeMs = (now - sessionStartMs).coerceAtLeast(0L)
    val uptimeLabel = formatUptime(uptimeMs)
    // LATENCY cell removed — it surfaced
    // `now - status.lastReportAtMs`, which grows without bound
    // during idle. Reads as "12h 04m latency" on a long quiet
    // link, which is misleading. Diagnostics already exposes the
    // precise value as part of `LiveRateHero`.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp, start = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatTripletCell(label = "REPORTS", value = reportsLabel)
        StatTripletCell(label = "UPTIME", value = uptimeLabel)
    }
}

@Composable
private fun StatTripletCell(label: String, value: String) {
    val palette = BluetrackTheme.palette
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = palette.fg3,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            color = palette.fg0,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatUptime(ms: Long): String {
    if (ms <= 0L) return "—"
    val secs = ms / 1_000L
    val h = secs / 3600L
    val m = (secs % 3600L) / 60L
    val s = secs % 60L
    return if (h > 0L) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Theme-aware surface. Earlier this hardcoded
    // `Color.White.copy(alpha = 0.08f)` which read as a lifted
    // panel on the dark palette but became invisible on light —
    // the touchpad zone literally vanished into the bg. Pull from
    // `palette.bg2` so the elevated surface contrast holds across
    // both themes.
    val palette = BluetrackTheme.palette
    Column(
        modifier
            .background(palette.bg2, RoundedCornerShape(8.dp))
            .padding(14.dp),
        content = content,
    )
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

/**
 * Kinetic-scroll fling tunables. `FLING_MIN_VELOCITY` is the
 * px/ms threshold finger velocity must exceed for a fling to
 * even start (slow drags die immediately, quick flicks coast).
 * `FLING_STOP_VELOCITY` is the lower bound the decay loop
 * exits at — set high enough that the final wheel tick is
 * still emitting visible motion, not a wasted micro-tick.
 */
private const val FLING_MIN_VELOCITY: Float = 0.35f
private const val FLING_STOP_VELOCITY: Float = 0.04f

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
