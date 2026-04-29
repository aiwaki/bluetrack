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
    private val gamepadReport = byteArrayOf(0, 0, 0, 0, 0)
    private val mouseReport = byteArrayOf(0, 0, 0, 0)
    @Volatile var sensitivity: Float = 2.0f

    fun updateCorrection(x: Float, y: Float) {
        correctionX.set(x.roundToInt())
        correctionY.set(y.roundToInt())
    }

    fun processMouseToStick(dx: Float, dy: Float, mode: HidMode, send: (ByteArray) -> Unit) {
        val rx = dx.toInt()
        val ry = dy.toInt()
        val cx = correctionX.get()
        val cy = correctionY.get()
        val sx = (((rx + cx) * sensitivity).toInt()).coerceIn(-127, 127)
        val sy = (((ry + cy) * sensitivity).toInt()).coerceIn(-127, 127)

        if (mode == HidMode.GAMEPAD) {
            gamepadReport[1] = sx.toByte(); gamepadReport[2] = sy.toByte()
            send(gamepadReport)
            deadmanJob?.cancel()
            deadmanJob = scope.launch {
                delay(20)
                gamepadReport[1] = 0; gamepadReport[2] = 0
                send(gamepadReport)
            }
        } else {
            mouseReport[1] = (rx + cx).coerceIn(-127, 127).toByte()
            mouseReport[2] = (ry + cy).coerceIn(-127, 127).toByte()
            send(mouseReport)
        }
        _telemetry.value = Telemetry(rx, ry, sx, sy)
    }
}

enum class HidMode { MOUSE, GAMEPAD }
data class Telemetry(val rawX: Int = 0, val rawY: Int = 0, val stickX: Int = 0, val stickY: Int = 0)
