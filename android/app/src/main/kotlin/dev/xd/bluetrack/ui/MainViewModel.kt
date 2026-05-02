package dev.xd.bluetrack.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xd.bluetrack.ble.BleHidGateway
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.Telemetry
import dev.xd.bluetrack.engine.TranslationEngine
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
    private var autopilotJob: Job? = null

    fun start() {
        if (started) return
        started = true
        ble.initialize()
        ble.register(_mode.value)
        startAutopilot()
    }

    fun toggle(gamepad: Boolean) {
        val mode = if (gamepad) HidMode.GAMEPAD else HidMode.MOUSE
        _mode.value = mode
        if (started) ble.register(mode)
    }

    fun processMotion(dx: Float, dy: Float, source: String = "External mouse") {
        ble.recordInput(source)
        engine.processMouseToStick(dx, dy, _mode.value) { ble.send(_mode.value, it) }
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
        autopilotJob?.cancel()
        autopilotJob = null
        ble.shutdown()
        started = false
    }

    private fun startAutopilot() {
        if (autopilotJob != null) return
        autopilotJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                if (started) ble.refreshCompatibility(announce = false)
            }
        }
    }
}
