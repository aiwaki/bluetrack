package dev.xd.bluetrack.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class TranslationEngine(private val scope: CoroutineScope) {
    private val correctionX = AtomicInteger(0)
    private val correctionY = AtomicInteger(0)
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry
    private var deadmanJob: Job? = null
    private val gamepadReport = byteArrayOf(0, 0, 0, 0, 0, 0)
    private val mouseReport = byteArrayOf(0, 0, 0, 0)
    private var mouseCarryX = 0f
    private var mouseCarryY = 0f
    @Volatile var sensitivity: Float = 2.0f

    fun updateCorrection(x: Float, y: Float) {
        correctionX.set(x.roundToInt())
        correctionY.set(y.roundToInt())
    }

    fun processMouseToStick(dx: Float, dy: Float, mode: HidMode, send: (ByteArray) -> Unit) {
        val rx = dx.roundToInt()
        val ry = dy.roundToInt()
        val cx = correctionX.get()
        val cy = correctionY.get()
        val sx = (((dx + cx) * sensitivity).roundToInt()).coerceIn(-127, 127)
        val sy = (((dy + cy) * sensitivity).roundToInt()).coerceIn(-127, 127)

        if (mode == HidMode.GAMEPAD) {
            gamepadReport[2] = sx.toByte(); gamepadReport[3] = sy.toByte()
            gamepadReport[4] = 0; gamepadReport[5] = 0
            send(gamepadReport)
            deadmanJob?.cancel()
            deadmanJob = scope.launch {
                delay(20)
                gamepadReport[2] = 0; gamepadReport[3] = 0
                gamepadReport[4] = 0; gamepadReport[5] = 0
                send(gamepadReport)
            }
        } else {
            val mouseX = quantizeMouseDelta(dx + cx, isX = true)
            val mouseY = quantizeMouseDelta(dy + cy, isX = false)
            mouseReport[1] = mouseX.toByte()
            mouseReport[2] = mouseY.toByte()
            send(mouseReport)
        }
        _telemetry.value = Telemetry(rx, ry, sx, sy)
    }

    private fun quantizeMouseDelta(delta: Float, isX: Boolean): Int {
        val carried = delta + if (isX) mouseCarryX else mouseCarryY
        val rounded = carried.roundToInt()
        val clamped = rounded.coerceIn(-127, 127)
        val nextCarry = carried - clamped
        if (isX) {
            mouseCarryX = nextCarry
        } else {
            mouseCarryY = nextCarry
        }
        return clamped
    }
}

enum class HidMode { MOUSE, GAMEPAD }
data class Telemetry(val rawX: Int = 0, val rawY: Int = 0, val stickX: Int = 0, val stickY: Int = 0)
