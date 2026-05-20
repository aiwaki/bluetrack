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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainViewModel(
    private val ble: BleHidGateway,
    private val engine: TranslationEngine,
) : ViewModel() {
    private val _mode = MutableStateFlow(HidMode.MOUSE)
    val mode: StateFlow<HidMode> = _mode
    val telemetry: StateFlow<Telemetry> = engine.telemetry
    val status = ble.status

    @Volatile private var started = false
    private val inputLock = Any()
    private val hidSenderLock = Any()
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingMode = HidMode.MOUSE
    private var lastQueuedInputAtMs = 0L
    private var lastRecordedInputAtMs = 0L
    private var lastRecordedInputSource: String? = null
    private var inputPacerJob: Job? = null
    private var hidSenderJob: Job? = null
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
            var lastReports = ble.status.value.lifetimeCounters.reports
            var lastFeedback = ble.status.value.lifetimeCounters.feedback
            while (isActive) {
                delay(1_000L)
                val currentReports = ble.status.value.lifetimeCounters.reports
                val currentFeedback = ble.status.value.lifetimeCounters.feedback
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
    }

    fun toggle(gamepad: Boolean) {
        val mode = if (gamepad) HidMode.GAMEPAD else HidMode.MOUSE
        _mode.value = mode
        synchronized(inputLock) {
            pendingDx = 0f
            pendingDy = 0f
            pendingMode = mode
        }
        hidOutputBuffer.clear()
        hidTransportGovernor.reset()
        touchMotionPredictor.reset()
        if (started) ble.register(mode)
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

    private fun ensureInputPacer() {
        if (inputPacerJob?.isActive == true) return
        inputPacerJob =
            viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(INPUT_TICK_MS)
                    val tickAtMs = SystemClock.elapsedRealtime()
                    inputDiagnostics.recordPacerTick(tickAtMs)
                    val frame =
                        synchronized(inputLock) {
                            val dx = pendingDx
                            val dy = pendingDy
                            val idle = SystemClock.elapsedRealtime() - lastQueuedInputAtMs > INPUT_IDLE_STOP_MS
                            if (abs(dx) <= INPUT_EPSILON && abs(dy) <= INPUT_EPSILON) {
                                if (idle) InputFrame.STOP else null
                            } else {
                                pendingDx = 0f
                                pendingDy = 0f
                                InputFrame(dx = dx, dy = dy, mode = pendingMode, queuedAtMs = lastQueuedInputAtMs)
                            }
                                ?: predictedInputFrame(tickAtMs, idle)
                        }
                    if (frame == InputFrame.STOP) {
                        inputDiagnostics.resetPacerClock()
                        break
                    }
                    frame ?: continue
                    inputDiagnostics.recordFrame(tickAtMs, frame.queuedAtMs)
                    engine.processMouseToStick(frame.dx, frame.dy, frame.mode) { report ->
                        enqueueHidReport(frame.mode, report)
                    }
                }
                val restart =
                    synchronized(inputLock) {
                        if (inputPacerJob == this@launch.coroutineContext[Job]) {
                            inputPacerJob = null
                            abs(pendingDx) > INPUT_EPSILON || abs(pendingDy) > INPUT_EPSILON
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
                viewModelScope.launch(Dispatchers.IO) {
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
        const val NANOS_PER_MS = 1_000_000L
        const val TOUCHPAD_SOURCE = "Touchpad"
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
