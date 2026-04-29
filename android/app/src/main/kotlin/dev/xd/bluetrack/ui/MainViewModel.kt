package dev.xd.bluetrack.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xd.bluetrack.ble.BleHidGateway
import dev.xd.bluetrack.engine.HidMode
import dev.xd.bluetrack.engine.Telemetry
import dev.xd.bluetrack.engine.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainViewModel(private val ble: BleHidGateway, private val engine: TranslationEngine) : ViewModel() {
    private val _mode = MutableStateFlow(HidMode.MOUSE)
    val mode: StateFlow<HidMode> = _mode
    val telemetry: StateFlow<Telemetry> = engine.telemetry
    val connection = ble.state

    init { ble.initialize(); ble.register(HidMode.MOUSE) }

    fun toggle(gamepad: Boolean) {
        val mode = if (gamepad) HidMode.GAMEPAD else HidMode.MOUSE
        _mode.value = mode
        ble.register(mode)
    }

    fun processMotion(dx: Float, dy: Float) {
        engine.processMouseToStick(dx, dy, _mode.value) { ble.send(_mode.value, it) }
    }
}
