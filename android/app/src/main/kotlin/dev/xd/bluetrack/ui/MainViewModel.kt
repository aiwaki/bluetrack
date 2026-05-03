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
    private var started = false
    private val inputLock = Any()
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingMode = HidMode.MOUSE
    private var lastQueuedInputAtMs = 0L
    private var lastRecordedInputAtMs = 0L
    private var lastRecordedInputSource: String? = null
    private var inputPacerJob: Job? = null

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
        if (started) ble.register(mode)
    }

    fun processMotion(dx: Float, dy: Float, source: String = "External mouse") {
        val now = SystemClock.elapsedRealtime()
        recordInputThrottled(source, now)
        synchronized(inputLock) {
            pendingDx += dx
            pendingDy += dy
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
        inputPacerJob?.cancel()
        inputPacerJob = null
        started = false
    }

    private fun ensureInputPacer() {
        if (inputPacerJob?.isActive == true) return
        inputPacerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(INPUT_TICK_MS)
                val frame = synchronized(inputLock) {
                    val dx = pendingDx
                    val dy = pendingDy
                    val idle = SystemClock.elapsedRealtime() - lastQueuedInputAtMs > INPUT_IDLE_STOP_MS
                    if (abs(dx) <= INPUT_EPSILON && abs(dy) <= INPUT_EPSILON) {
                        if (idle) InputFrame.STOP else null
                    } else {
                        pendingDx = 0f
                        pendingDy = 0f
                        InputFrame(dx = dx, dy = dy, mode = pendingMode)
                    }
                }
                if (frame == InputFrame.STOP) break
                frame ?: continue
                engine.processMouseToStick(frame.dx, frame.dy, frame.mode) { report ->
                    ble.send(frame.mode, report)
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
    ) {
        companion object {
            val STOP = InputFrame(0f, 0f, HidMode.MOUSE)
        }
    }

    private companion object {
        const val INPUT_TICK_MS = 8L
        const val INPUT_IDLE_STOP_MS = 120L
        const val INPUT_STATUS_INTERVAL_MS = 250L
        const val INPUT_EPSILON = 0.005f
    }
}
