package dev.xd.bluetrack.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xd.bluetrack.ble.BleHidGateway
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.Telemetry
import dev.xd.bluetrack.engine.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlin.math.abs

class MainViewModel(
    private val ble: BleHidGateway,
    private val engine: TranslationEngine,
) : ViewModel() {
    private val _mode = MutableStateFlow(HidMode.MOUSE)
    val mode: StateFlow<HidMode> = _mode

    /**
     * UI-only surface selection on the Hub. TOUCHPAD renders the
     * virtual trackpad; MOUSE renders the mirror passthrough.
     * Both keep `HidMode = MOUSE` on the gateway — the gamepad
     * fullscreen flip path is unaffected and still owned by
     * `MainActivity`'s `gamepadActive` boolean.
     */
    private val _surfaceMode = MutableStateFlow(TouchpadSurfaceMode.TOUCHPAD)
    val surfaceMode: StateFlow<TouchpadSurfaceMode> = _surfaceMode
    val telemetry: StateFlow<Telemetry> = engine.telemetry
    val status = ble.status

    @Volatile private var started = false
    private val inputLock = Any()
    private val hidSenderLock = Any()

    // Dedicated single-thread executors for the pacer and the HID
    // sender. Pinning each coroutine to a single OS thread means
    // `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`
    // sticks across the coroutine's lifetime (vs. `Dispatchers.IO`
    // / `Default` which migrate coroutines across pool threads on
    // every suspension and lose the priority hint). The named
    // threads also surface cleanly in `adb shell top` / Studio's
    // CPU profiler when investigating future stalls.
    private val pacerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bluetrack-pacer").apply { priority = Thread.MAX_PRIORITY }
    }
    private val pacerDispatcher = pacerExecutor.asCoroutineDispatcher()
    private val senderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bluetrack-hid-sender").apply { priority = Thread.MAX_PRIORITY }
    }
    private val senderDispatcher = senderExecutor.asCoroutineDispatcher()
    private var pendingDx = 0f
    private var pendingDy = 0f

    // Two-finger touchpad scroll feeds the wheel byte on the
    // composite mouse report. Lives in its own accumulator (not
    // pendingDy) so a 1-finger drag and a 2-finger scroll within
    // the same gesture do not collide on the same delta channel.
    private var pendingWheelDy = 0f

    // Two-finger horizontal scroll → AC Pan (wheel byte 4). Separate
    // accumulator from pendingWheelDy so a diagonal 2-finger drag feeds
    // both wheel axes independently without one channel clobbering the
    // other.
    private var pendingWheelDx = 0f
    private var pendingMode = HidMode.MOUSE
    private var lastQueuedInputAtMs = 0L
    private var lastRecordedInputAtMs = 0L
    private var lastRecordedInputSource: String? = null
    private var inputPacerJob: Job? = null
    private var hidSenderJob: Job? = null
    private var keepaliveJob: Job? = null

    // Keyboard key queue. Taps from the relay are paced through this
    // channel so a burst (paste, fast typing, autocorrect churn) is
    // serialised with a real hold + gap between keys — otherwise the
    // host drops the first / rapid keys when reports arrive back to
    // back with no key-down dwell.
    private val keyChannel = Channel<IntArray>(capacity = Channel.UNLIMITED)
    private var keyPumpJob: Job? = null
    private val hidOutputBuffer = HidOutputBuffer()
    private val hidTransportGovernor = HidTransportGovernor()
    private val touchMotionPredictor = TouchMotionPredictor()
    private val inputDiagnostics = InputDiagnostics()

    /**
     * Process-lifetime 60-sample rolling rate windows for the
     * Diagnostics route. Earlier the screen owned its own sampler
     * inside a `LaunchedEffect`, which meant the wave reset
     * whenever the user navigated away from Diag — so opening the
     * route after a long Hub touchpad session showed a flat zero
     * line even though the gateway had been busy. Sampling at
     * the ViewModel keeps the window alive for the lifetime of
     * the activity, which is the natural "since this app launch"
     * scope a user expects.
     */
    private val _hidRateWindow = MutableStateFlow<List<Float>>(emptyList())
    val hidRateWindow: StateFlow<List<Float>> = _hidRateWindow
    private val _feedbackRateWindow = MutableStateFlow<List<Float>>(emptyList())
    val feedbackRateWindow: StateFlow<List<Float>> = _feedbackRateWindow

    init {
        viewModelScope.launch(Dispatchers.Default) {
            // Seed both baselines but DROP the first computed
            // delta. At VM construction the gateway's StateFlow
            // is still in its initial empty state — the persisted
            // `lifetimeCounters` from `LifetimeCountersStore` only
            // surfaces in `_status` once the first updateStatus()
            // fires (typically when the first HID report is sent
            // or compatibility is refreshed). If we count that
            // first sample we record the entire persisted lifetime
            // total as a single 1-second delta (e.g. 3802/s on a
            // device with 3802 reports retained). Skipping the
            // first tick lets the seed settle on the real disk
            // value before deltas start accumulating.
            var lastReports = ble.status.value.lifetimeCounters.reports
            var lastFeedback = ble.status.value.lifetimeCounters.feedback
            var primed = false
            while (isActive) {
                delay(1_000L)
                val currentReports = ble.status.value.lifetimeCounters.reports
                val currentFeedback = ble.status.value.lifetimeCounters.feedback
                if (!primed) {
                    lastReports = currentReports
                    lastFeedback = currentFeedback
                    primed = true
                    continue
                }
                val dR = (currentReports - lastReports).coerceAtLeast(0L)
                val dF = (currentFeedback - lastFeedback).coerceAtLeast(0L)
                lastReports = currentReports
                lastFeedback = currentFeedback
                _hidRateWindow.value = (_hidRateWindow.value + dR.toFloat()).takeLast(60)
                _feedbackRateWindow.value = (_feedbackRateWindow.value + dF.toFloat()).takeLast(60)
            }
        }
    }

    fun start() {
        started = true
        ble.maintainRegistration(_mode.value)
        ensureKeepalive()
    }

    fun toggle(gamepad: Boolean) {
        val mode = if (gamepad) HidMode.GAMEPAD else HidMode.MOUSE
        _mode.value = mode
        synchronized(inputLock) {
            pendingDx = 0f
            pendingDy = 0f
            pendingWheelDy = 0f
            pendingWheelDx = 0f
            pendingMode = mode
        }
        hidOutputBuffer.clear()
        hidTransportGovernor.reset()
        touchMotionPredictor.reset()
        if (started) ble.register(mode)
    }

    /**
     * Swap the Hub input surface. Drops pending motion/scroll so a
     * mid-gesture flip from TOUCHPAD to MOUSE does not flush a
     * stale finger drag into the mirror path the moment the user's
     * physical mouse takes over.
     */
    fun setSurfaceMode(mode: TouchpadSurfaceMode) {
        if (_surfaceMode.value == mode) return
        _surfaceMode.value = mode
        synchronized(inputLock) {
            pendingDx = 0f
            pendingDy = 0f
            pendingWheelDy = 0f
            pendingWheelDx = 0f
        }
        touchMotionPredictor.reset()
    }

    /**
     * Forward a mouse button press / release from the mirror
     * passthrough surface to the HID engine. [buttonMask] uses
     * Android's `MotionEvent.BUTTON_PRIMARY/SECONDARY/TERTIARY`
     * encoding (1 / 2 / 4) which matches HID button bits
     * directly. Mouse mode only.
     */
    fun processMouseButton(
        buttonMask: Int,
        pressed: Boolean,
    ) {
        if (_mode.value != HidMode.MOUSE) return
        recordInputThrottled("Mirror mouse", SystemClock.elapsedRealtime())
        engine.setMouseButton(buttonMask, pressed) { report ->
            enqueueHidReport(HidMode.MOUSE, report)
        }
    }

    /**
     * Synthesise a momentary click from a touchpad tap. Fires
     * `press` → `delay(CLICK_HOLD_MS)` → `release` on a coroutine
     * so the host sees a proper hold-and-release event instead of
     * a microsecond pulse — macOS and Windows both gate
     * double-click detection on a real interval between the two
     * sides, and back-to-back press+release at HID-wire speed
     * can get filtered as input noise. 40 ms matches the lower
     * bound of a real human button-down on a desktop mouse.
     *
     * Two rapid taps from the user therefore land on the host as
     *   P  R     P  R
     *   |--|----|--|
     *   0  40   ~80 120ms
     * which any standard double-click detector accepts as a
     * legitimate text-select.
     */
    private val clickMutex = Mutex()

    fun processMouseClick(buttonMask: Int) {
        if (_mode.value != HidMode.MOUSE) return
        val now = SystemClock.elapsedRealtime()
        recordInputThrottled("Touchpad", now)
        viewModelScope.launch(Dispatchers.Default) {
            // Serialise click sequences so a second tap that
            // lands during the in-flight press → release of the
            // first does not collide on the shared `mouseButtons`
            // bit. Without the mutex two rapid 1-finger taps
            // collapse into a single press + release because the
            // bit is already set when the second coroutine
            // emits its `press`. The host then sees one click,
            // never two, so double-click text-select never
            // triggers. With the mutex each tap runs a clean
            // P → hold → R → small gap so concurrent taps queue
            // up as proper sequential clicks on the wire.
            clickMutex.withLock {
                engine.setMouseButton(buttonMask, true) { report ->
                    enqueueHidReport(HidMode.MOUSE, report)
                }
                delay(CLICK_HOLD_MS)
                engine.setMouseButton(buttonMask, false) { report ->
                    enqueueHidReport(HidMode.MOUSE, report)
                }
                delay(CLICK_GAP_MS)
            }
        }
    }

    fun beginTouchGesture() {
        inputDiagnostics.resetTouchClock()
        synchronized(inputLock) {
            touchMotionPredictor.reset()
        }
        if (_mode.value == HidMode.GAMEPAD) {
            ble.nudgeGamepadDiscovery("touch gesture")
        }
    }

    /**
     * Press or release a named gamepad button (A / B / X / Y /
     * LB / LT / RB / RT / BACK / START / GUIDE / L3 / R3). The
     * engine flips the corresponding bit on the in-memory
     * composite report and enqueues a fresh report on the HID
     * transport buffer — buttons latch, so a held press keeps
     * the bit set across subsequent stick-driven reports.
     *
     * Step 5 wired the `GamepadSurface.onButton` callback to a
     * no-op pending this API; step 9c lights it up for real.
     */
    fun processGamepadButton(
        label: String,
        pressed: Boolean,
    ) {
        if (_mode.value != HidMode.GAMEPAD) return
        engine.setGamepadButton(label, pressed) { report ->
            enqueueHidReport(HidMode.GAMEPAD, report)
        }
    }

    /**
     * Set the hat-switch byte (0..7 = direction, 8 = neutral)
     * and push a fresh report. `GamepadSurface.onButton`
     * dispatches D-pad events here with the canvas's hat
     * encoding already in the right shape.
     */
    fun processGamepadHat(hat: Int) {
        if (_mode.value != HidMode.GAMEPAD) return
        engine.setGamepadHat(hat) { report ->
            enqueueHidReport(HidMode.GAMEPAD, report)
        }
    }

    /**
     * Fire a keyboard shortcut chord (key down + up) from a
     * Mac-trackpad gesture (pinch zoom, 3/4-finger swipe). [modifier]
     * is an OR of `HidKeys.MOD_*`; [keycode] is a HID Usage page 0x07
     * code (0 = modifier-only chord). Mouse mode only.
     *
     * The keyboard report path is orthogonal to the active mouse path:
     * it rides its own pass-through queue in [HidOutputBuffer] (report
     * ID 3) so a chord never disturbs in-flight cursor motion.
     */
    fun tapHidKey(
        modifier: Int,
        keycode: Int,
    ) {
        if (_mode.value != HidMode.MOUSE) return
        recordInputThrottled("Keyboard", SystemClock.elapsedRealtime())
        engine.tapKey(modifier, keycode) { report ->
            enqueueHidReport(HidMode.KEYBOARD, report)
        }
    }

    /**
     * Tap a key from the full on-screen keyboard surface. Unlike
     * [tapHidKey] (a trackpad-gesture chord that only fires in mouse
     * mode), this has NO mode gate: the keyboard report path uses its
     * own report ID 3 and is orthogonal to the active mouse/gamepad
     * mode, so the keyboard surface can type whenever it is open.
     * [modifier] is an OR of `HidKeys.MOD_*`; [keycode] a Usage-0x07
     * code.
     */
    fun keyboardTap(
        modifier: Int,
        keycode: Int,
    ) {
        recordInputThrottled("Keyboard", SystemClock.elapsedRealtime())
        keyChannel.trySend(intArrayOf(modifier, keycode))
        ensureKeyPump()
    }

    private fun ensureKeyPump() {
        if (keyPumpJob?.isActive == true) return
        keyPumpJob = viewModelScope.launch(Dispatchers.Default) {
            for (key in keyChannel) {
                val modifier = key[0]
                val keycode = key[1]
                engine.processKeyDown(modifier, keycode) { report ->
                    enqueueHidReport(HidMode.KEYBOARD, report)
                }
                delay(KEY_HOLD_MS)
                engine.processKeyUp(modifier, keycode) { report ->
                    enqueueHidReport(HidMode.KEYBOARD, report)
                }
                delay(KEY_GAP_MS)
            }
        }
    }

    /**
     * Touchpad two-finger scroll → wheel byte. Caller supplies a
     * pre-scaled wheel delta (typically `−touchDy / pxPerTick`).
     * Mouse mode only — gamepad mode silently ignores so a stray
     * 2-finger swipe over the gamepad layout never emits stick
     * input. Drains alongside motion in the input pacer.
     */
    fun processScroll(
        wheelDy: Float,
        wheelDx: Float = 0f,
    ) {
        if (_mode.value != HidMode.MOUSE) return
        val now = SystemClock.elapsedRealtime()
        recordInputThrottled("Touchpad", now)
        synchronized(inputLock) {
            pendingWheelDy += wheelDy
            pendingWheelDx += wheelDx
            lastQueuedInputAtMs = now
        }
        ensureInputPacer()
    }

    fun processMotion(
        dx: Float,
        dy: Float,
        source: String = "External mouse",
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastQueuedInputAtMs <= 0L || now - lastQueuedInputAtMs > INPUT_GESTURE_RESET_MS) {
            inputDiagnostics.resetTouchClock()
        }
        inputDiagnostics.recordTouch(now)
        recordInputThrottled(source, now)
        synchronized(inputLock) {
            val motion =
                if (source == TOUCHPAD_SOURCE) {
                    touchMotionPredictor.recordTouch(dx, dy, now)
                } else {
                    touchMotionPredictor.reset()
                    TouchMotionPredictor.MotionDelta(dx, dy)
                }
            pendingDx += motion.dx
            pendingDy += motion.dy
            pendingMode = _mode.value
            lastQueuedInputAtMs = now
        }
        ensureInputPacer()
    }

    fun refreshCompatibility() {
        ble.refreshCompatibility()
    }

    fun connectHost() {
        ble.connectBondedHost()
    }

    /**
     * Tap-to-connect a specific bonded host. Used by the Hub
     * TrustCard recommended-host list so the user can wake a
     * bonded computer from sleep without waiting for the
     * auto-connect tick.
     */
    fun connectHost(name: String) {
        ble.connectBondedHost(name)
    }

    /**
     * Manual disconnect from the active HID host. Surfaced as the
     * TrustCard recommended-host "DISCONNECT" pill so the user can
     * tear down a sticky link without toggling Bluetooth radio.
     */
    fun disconnectActiveHost() {
        ble.disconnectActiveHost()
    }

    /**
     * Push the auto-connect toggle through to the gateway. Settings
     * route owns the user-facing preference (DataStore-backed); this
     * is the wire from the toggle to the runtime behaviour.
     */
    fun setAutoConnectEnabled(enabled: Boolean) {
        ble.setAutoConnectEnabled(enabled)
    }

    fun bluetoothPermissionMissing() {
        ble.reportPermissionMissing()
    }

    fun bluetoothEnableRequested() {
        started = false
        ble.reportBluetoothEnableRequested()
    }

    fun bluetoothDisabled() {
        started = false
        ble.reportBluetoothDisabled()
    }

    fun discoverable(seconds: Int) {
        ble.reportDiscoverable(seconds)
    }

    fun discoverabilityRequested(auto: Boolean) {
        ble.reportDiscoverabilityRequested(auto)
    }

    fun discoverabilityCancelled() {
        ble.reportDiscoverableRejected()
    }

    fun shutdown() {
        detach()
        ble.shutdown()
    }

    /** Drop the TOFU-pinned host identity (re-pair on next handshake). */
    fun forgetTrustedHost() {
        ble.forgetTrustedHost()
    }

    /**
     * Unpair a bonded device by name. Surfaced for the Hosts
     * route ✕ button. Forgets the TOFU host identity too if the
     * unpaired device is the currently-pinned trust target so
     * the user doesn't have to do it in two steps.
     */
    fun removeBondedDevice(name: String) {
        val wasActiveHost = ble.status.value.host == name
        val removed = ble.removeBondedDevice(name)
        // Only drop the TOFU pin when we actually unpaired the
        // device. Codex review on PR #57 flagged that we used to
        // clear the trust pin even if `removeBond()` returned
        // false (reflection refused, OS denied, etc.), which left
        // the bond on the adapter while wiping the trust state —
        // a confusing partial unpair.
        if (removed && wasActiveHost) {
            ble.forgetTrustedHost()
        }
    }

    /** Wipe persisted lifetime counters back to zero. */
    fun resetLifetimeCounters() {
        ble.resetLifetimeCounters()
    }

    fun detach() {
        started = false
        inputPacerJob?.cancel()
        inputPacerJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        synchronized(hidSenderLock) {
            hidSenderJob?.cancel()
            hidSenderJob = null
        }
        hidOutputBuffer.clear()
        hidTransportGovernor.reset()
        touchMotionPredictor.reset()
        inputDiagnostics.resetPacerClock()
        inputDiagnostics.resetTouchClock()
    }

    override fun onCleared() {
        super.onCleared()
        // Coroutine cancellation in `detach()` only stops the
        // current loops; the executor pools live longer (one per VM
        // instance), so explicitly shut them down here so their
        // single threads don't leak past VM destruction.
        pacerExecutor.shutdown()
        senderExecutor.shutdown()
    }

    private fun ensureInputPacer() {
        if (inputPacerJob?.isActive == true) return
        inputPacerJob =
            viewModelScope.launch(pacerDispatcher) {
                // Bump scheduling priority so the pacer's 8 ms
                // delay loop doesn't lose ticks to a Compose
                // recomposition burst or background indexing.
                // URGENT_AUDIO matches what AudioTrack uses — same
                // real-time-input contract here: a missed tick is
                // perceived as cursor stutter.
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
                )
                while (isActive) {
                    delay(INPUT_TICK_MS)
                    val tickAtMs = SystemClock.elapsedRealtime()
                    inputDiagnostics.recordPacerTick(tickAtMs)
                    val frame =
                        synchronized(inputLock) {
                            val dx = pendingDx
                            val dy = pendingDy
                            val wheel = pendingWheelDy
                            val wheelX = pendingWheelDx
                            val idle = SystemClock.elapsedRealtime() - lastQueuedInputAtMs > INPUT_IDLE_STOP_MS
                            val motionEmpty = abs(dx) <= INPUT_EPSILON && abs(dy) <= INPUT_EPSILON
                            val wheelEmpty = abs(wheel) <= INPUT_EPSILON && abs(wheelX) <= INPUT_EPSILON
                            if (motionEmpty && wheelEmpty) {
                                if (idle) InputFrame.STOP else null
                            } else {
                                pendingDx = 0f
                                pendingDy = 0f
                                pendingWheelDy = 0f
                                pendingWheelDx = 0f
                                InputFrame(
                                    dx = dx,
                                    dy = dy,
                                    wheelDy = wheel,
                                    wheelDx = wheelX,
                                    mode = pendingMode,
                                    queuedAtMs = lastQueuedInputAtMs,
                                )
                            }
                                ?: predictedInputFrame(tickAtMs, idle)
                        }
                    if (frame == InputFrame.STOP) {
                        inputDiagnostics.resetPacerClock()
                        break
                    }
                    frame ?: continue
                    inputDiagnostics.recordFrame(tickAtMs, frame.queuedAtMs)
                    // Emit wheel BEFORE motion so a frame that
                    // carries both (rare on a real touchpad — the
                    // touch listener routes a gesture as either
                    // motion or scroll) does not let the motion
                    // report's wheel=0 byte clobber an intended
                    // scroll tick.
                    if ((abs(frame.wheelDy) > INPUT_EPSILON || abs(frame.wheelDx) > INPUT_EPSILON) &&
                        frame.mode == HidMode.MOUSE
                    ) {
                        engine.processWheel(frame.wheelDy, frame.wheelDx) { report ->
                            enqueueHidReport(frame.mode, report)
                        }
                    }
                    if (abs(frame.dx) > INPUT_EPSILON || abs(frame.dy) > INPUT_EPSILON) {
                        engine.processMouseToStick(frame.dx, frame.dy, frame.mode) { report ->
                            enqueueHidReport(frame.mode, report)
                        }
                    }
                }
                val restart =
                    synchronized(inputLock) {
                        if (inputPacerJob == this@launch.coroutineContext[Job]) {
                            inputPacerJob = null
                            abs(pendingDx) > INPUT_EPSILON ||
                                abs(pendingDy) > INPUT_EPSILON ||
                                abs(pendingWheelDy) > INPUT_EPSILON ||
                                abs(pendingWheelDx) > INPUT_EPSILON
                        } else {
                            false
                        }
                    }
                if (restart && started) ensureInputPacer()
            }
    }

    private fun enqueueHidReport(
        mode: HidMode,
        report: ByteArray,
    ) {
        if (!started) return
        hidOutputBuffer.enqueue(mode, report, queuedAtMs = SystemClock.elapsedRealtime())
        ensureHidSender()
    }

    private fun ensureHidSender() {
        synchronized(hidSenderLock) {
            if (hidSenderJob?.isActive == true) return
            hidSenderJob =
                viewModelScope.launch(senderDispatcher) {
                    // Match the pacer's priority — sender thread
                    // owns the BluetoothHidDevice.sendReport call
                    // which is the actual latency bottleneck. If
                    // it loses CPU to a background pool task the
                    // entire output queue stalls and the cursor
                    // stutters when the drain finally happens.
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
                    )
                    while (isActive) {
                        val transportDelayMs = hidTransportGovernor.delayBeforeSend(SystemClock.elapsedRealtime())
                        if (transportDelayMs > 0L) delay(transportDelayMs)
                        if (!isActive) break

                        val output = hidOutputBuffer.poll() ?: break
                        val sendStartedMs = SystemClock.elapsedRealtime()
                        inputDiagnostics.recordOutputFrame(sendStartedMs, output.queuedAtMs)
                        val sendStartedNs = SystemClock.elapsedRealtimeNanos()
                        ble.send(output.mode, output.report)
                        val durationNs = SystemClock.elapsedRealtimeNanos() - sendStartedNs
                        val nowMs = SystemClock.elapsedRealtime()
                        hidTransportGovernor.recordSend(durationNs / NANOS_PER_MS, nowMs)
                        inputDiagnostics.recordHidSend(durationNs = durationNs, nowMs = nowMs)
                    }
                    val restart =
                        synchronized(hidSenderLock) {
                            if (hidSenderJob == this@launch.coroutineContext[Job]) {
                                hidSenderJob = null
                                hidOutputBuffer.hasPending() && started
                            } else {
                                false
                            }
                        }
                    if (restart) ensureHidSender()
                }
        }
    }

    /**
     * Low-rate idle keepalive. While connected, in mouse mode, and idle
     * for at least [KEEPALIVE_IDLE_MS], re-send the current mouse state
     * (held buttons preserved, zero motion) every [KEEPALIVE_TICK_MS].
     * The transmission keeps the BR/EDR link out of deep sniff so the
     * first real input after a pause is not stalled by sniff-wake
     * latency — the dominant cause of the "cursor lags on the first
     * move after idle" feel (logcat showed maxHidSend spikes with a
     * clean pacer/queue). Skipped whenever the HID sender is actively
     * draining real input, so a keepalive never races a real sendReport.
     */
    private fun ensureKeepalive() {
        if (keepaliveJob?.isActive == true) return
        keepaliveJob =
            viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(KEEPALIVE_TICK_MS)
                    if (!started) continue
                    if (_mode.value != HidMode.MOUSE) continue
                    if (hidSenderJob?.isActive == true) continue
                    if (ble.status.value.host == null) continue
                    val idleMs = SystemClock.elapsedRealtime() - lastQueuedInputAtMs
                    if (idleMs < KEEPALIVE_IDLE_MS) continue
                    // Bypasses the report/lifetime counters so the idle
                    // keepalive does not keep the Hub / Diagnostics rate
                    // graphs permanently alive.
                    ble.sendKeepalive(engine.keepaliveReport())
                }
            }
    }

    private fun recordInputThrottled(
        source: String,
        now: Long,
    ) {
        if (source == lastRecordedInputSource && now - lastRecordedInputAtMs < INPUT_STATUS_INTERVAL_MS) return
        lastRecordedInputSource = source
        lastRecordedInputAtMs = now
        ble.recordInput(source)
    }

    private data class InputFrame(
        val dx: Float,
        val dy: Float,
        val mode: HidMode,
        val queuedAtMs: Long,
        val wheelDy: Float = 0f,
        val wheelDx: Float = 0f,
    ) {
        companion object {
            val STOP = InputFrame(0f, 0f, HidMode.MOUSE, 0L)
        }
    }

    private companion object {
        const val INPUT_TICK_MS = 8L
        const val INPUT_IDLE_STOP_MS = 120L
        const val INPUT_GESTURE_RESET_MS = 1000L
        const val INPUT_STATUS_INTERVAL_MS = 250L
        const val INPUT_EPSILON = 0.005f
        const val CLICK_HOLD_MS = 40L
        const val CLICK_GAP_MS = 20L

        // Keyboard key-down dwell + inter-key gap. A real hold +
        // release window so the host registers each key (incl. repeats)
        // even when the relay bursts a paste or fast typing.
        const val KEY_HOLD_MS = 12L
        const val KEY_GAP_MS = 10L
        const val NANOS_PER_MS = 1_000_000L
        const val TOUCHPAD_SOURCE = "Touchpad"

        // Idle keepalive cadence: re-send mouse state every 500 ms once
        // idle ≥ 400 ms, keeping the BR/EDR link out of deep sniff so the
        // first real input after a pause skips the sniff-wake stall.
        const val KEEPALIVE_TICK_MS = 500L
        const val KEEPALIVE_IDLE_MS = 400L
    }

    private fun predictedInputFrame(
        tickAtMs: Long,
        idle: Boolean,
    ): InputFrame? {
        if (idle || pendingMode != HidMode.MOUSE) return null
        val predicted = touchMotionPredictor.predict(tickAtMs) ?: return null
        return InputFrame(
            dx = predicted.dx,
            dy = predicted.dy,
            mode = pendingMode,
            queuedAtMs = tickAtMs,
        )
    }
}
