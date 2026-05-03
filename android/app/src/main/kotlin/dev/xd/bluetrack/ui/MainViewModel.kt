package dev.xd.bluetrack.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xd.bluetrack.ble.BleHidGateway
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.Telemetry
import dev.xd.bluetrack.engine.TranslationEngine
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(private val ble: BleHidGateway, private val engine: TranslationEngine) : ViewModel() {
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

    fun processMotion(dx: Float, dy: Float, source: String = "External mouse") {
        val now = SystemClock.elapsedRealtime()
        if (lastQueuedInputAtMs <= 0L || now - lastQueuedInputAtMs > INPUT_GESTURE_RESET_MS) {
            inputDiagnostics.resetTouchClock()
        }
        inputDiagnostics.recordTouch(now)
        recordInputThrottled(source, now)
        synchronized(inputLock) {
            val motion = if (source == TOUCHPAD_SOURCE) {
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
        inputPacerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(INPUT_TICK_MS)
                val tickAtMs = SystemClock.elapsedRealtime()
                inputDiagnostics.recordPacerTick(tickAtMs)
                val frame = synchronized(inputLock) {
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
            val restart = synchronized(inputLock) {
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

    private fun enqueueHidReport(mode: HidMode, report: ByteArray) {
        if (!started) return
        hidOutputBuffer.enqueue(mode, report, queuedAtMs = SystemClock.elapsedRealtime())
        ensureHidSender()
    }

    private fun ensureHidSender() {
        synchronized(hidSenderLock) {
            if (hidSenderJob?.isActive == true) return
            hidSenderJob = viewModelScope.launch(Dispatchers.IO) {
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
                val restart = synchronized(hidSenderLock) {
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

    private fun recordInputThrottled(source: String, now: Long) {
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

    private fun predictedInputFrame(tickAtMs: Long, idle: Boolean): InputFrame? {
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
