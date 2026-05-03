package dev.xd.bluetrack.ui

import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.ViewModel
import dev.xd.bluetrack.ble.BleHidGateway
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.Telemetry
import dev.xd.bluetrack.engine.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(private val ble: BleHidGateway, private val engine: TranslationEngine) : ViewModel() {
    private val _mode = MutableStateFlow(HidMode.MOUSE)
    val mode: StateFlow<HidMode> = _mode
    val telemetry: StateFlow<Telemetry> = engine.telemetry
    val status = ble.status
    private var started = false
    private val inputLock = Any()
    private val inputPacerState = InputPacerState()
    private val inputThread = HandlerThread("BluetrackInputPacer", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val inputHandler = Handler(inputThread.looper)
    private var inputPacerRunning = false
    private var inputThreadClosed = false
    private var nextInputTickAtMs = 0L
    private var lastRecordedInputAtMs = 0L
    private var lastRecordedInputSource: String? = null
    private val inputTick = object : Runnable {
        override fun run() {
            val frame = synchronized(inputLock) {
                if (!inputPacerRunning) {
                    return
                }
                when (val decision = inputPacerState.nextFrame(SystemClock.uptimeMillis())) {
                    is InputPacerDecision.Frame -> decision
                    InputPacerDecision.Wait -> null
                    InputPacerDecision.Stop -> {
                        inputPacerRunning = false
                        null
                    }
                }
            }

            if (frame != null) {
                engine.processMouseToStick(frame.dx, frame.dy, frame.mode) { report ->
                    ble.send(frame.mode, report)
                }
            }

            val nextTick = synchronized(inputLock) {
                if (!inputPacerRunning) return
                val now = SystemClock.uptimeMillis()
                nextInputTickAtMs = (nextInputTickAtMs + INPUT_TICK_MS)
                    .coerceAtLeast(now + INPUT_TICK_MS)
                nextInputTickAtMs
            }
            inputHandler.postAtTime(this, nextTick)
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
            inputPacerState.reset(mode)
        }
        if (started) ble.register(mode)
    }

    fun processMotion(dx: Float, dy: Float, source: String = "External mouse") {
        val now = SystemClock.uptimeMillis()
        recordInputThrottled(source, SystemClock.elapsedRealtime())
        synchronized(inputLock) {
            inputPacerState.queue(dx, dy, _mode.value, now)
        }
        ensureInputPacer(now)
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
        inputHandler.removeCallbacks(inputTick)
        synchronized(inputLock) {
            inputPacerRunning = false
            inputPacerState.reset(_mode.value)
        }
        closeInputThread()
        started = false
    }

    override fun onCleared() {
        detach()
        super.onCleared()
    }

    private fun ensureInputPacer(nowMs: Long) {
        var shouldPost = false
        synchronized(inputLock) {
            if (!inputPacerRunning) {
                inputPacerRunning = true
                nextInputTickAtMs = nowMs
                shouldPost = true
            }
        }
        if (shouldPost) {
            inputHandler.post(inputTick)
        }
    }

    private fun closeInputThread() {
        if (inputThreadClosed) return
        inputThreadClosed = true
        inputThread.quitSafely()
    }

    private fun recordInputThrottled(source: String, now: Long) {
        if (source == lastRecordedInputSource && now - lastRecordedInputAtMs < INPUT_STATUS_INTERVAL_MS) return
        lastRecordedInputSource = source
        lastRecordedInputAtMs = now
        ble.recordInput(source)
    }

    private companion object {
        const val INPUT_TICK_MS = 8L
        const val INPUT_STATUS_INTERVAL_MS = 250L
    }
}
