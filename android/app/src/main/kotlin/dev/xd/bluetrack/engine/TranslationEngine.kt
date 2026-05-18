package dev.xd.bluetrack.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class TranslationEngine(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val correctionX = AtomicInteger(0)
    private val correctionY = AtomicInteger(0)
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry
    private var deadmanJob: Job? = null
    private val gamepadReport = GamepadReportFormat.neutralReport()
    private val mouseReport = byteArrayOf(0, 0, 0, 0)
    private var mouseCarryX = 0f
    private var mouseCarryY = 0f
    private var lastTelemetryAtMs = -1L

    @Volatile var sensitivity: Float = 2.0f

    fun updateCorrection(
        x: Float,
        y: Float,
    ) {
        correctionX.set(x.roundToInt())
        correctionY.set(y.roundToInt())
    }

    fun processMouseToStick(
        dx: Float,
        dy: Float,
        mode: HidMode,
        send: (ByteArray) -> Unit,
    ) {
        val rx = dx.roundToInt()
        val ry = dy.roundToInt()
        val cx = correctionX.get()
        val cy = correctionY.get()
        val sx = (((dx + cx) * sensitivity).roundToInt()).coerceIn(-127, 127)
        val sy = (((dy + cy) * sensitivity).roundToInt()).coerceIn(-127, 127)

        if (mode == HidMode.GAMEPAD) {
            gamepadReport[GamepadReportFormat.HAT_INDEX] = GamepadReportFormat.HAT_NEUTRAL
            gamepadReport[GamepadReportFormat.LEFT_X_INDEX] = sx.toByte()
            gamepadReport[GamepadReportFormat.LEFT_Y_INDEX] = sy.toByte()
            gamepadReport[GamepadReportFormat.RIGHT_X_INDEX] = 0
            gamepadReport[GamepadReportFormat.RIGHT_Y_INDEX] = 0
            send(gamepadReport)
            deadmanJob?.cancel()
            deadmanJob =
                scope.launch {
                    delay(20)
                    gamepadReport[GamepadReportFormat.HAT_INDEX] = GamepadReportFormat.HAT_NEUTRAL
                    gamepadReport[GamepadReportFormat.LEFT_X_INDEX] = 0
                    gamepadReport[GamepadReportFormat.LEFT_Y_INDEX] = 0
                    gamepadReport[GamepadReportFormat.RIGHT_X_INDEX] = 0
                    gamepadReport[GamepadReportFormat.RIGHT_Y_INDEX] = 0
                    send(gamepadReport)
                }
        } else {
            val mouseX = quantizeMouseDelta(dx + cx, isX = true)
            val mouseY = quantizeMouseDelta(dy + cy, isX = false)
            mouseReport[1] = mouseX.toByte()
            mouseReport[2] = mouseY.toByte()
            send(mouseReport)
        }
        publishTelemetry(Telemetry(rx, ry, sx, sy))
    }

    /**
     * Flip a single named gamepad button on / off, persist the
     * bit on the in-memory report, and emit the report via the
     * supplied [send] callback. Returns `true` when the label
     * matched a known button; `false` for unknown labels (the
     * caller can fall back to D-pad parsing).
     *
     * Buttons latch — `pressed = false` only clears the named
     * bit; other buttons stay set. The stick-update path resets
     * axes + hat after 20 ms but never touches the button bytes,
     * so a held button keeps emitting in subsequent reports.
     *
     * Thread-safety: writes target the same `gamepadReport`
     * array as `processMouseToStick`. Both run on the existing
     * input pacer (single dispatcher), so no extra
     * synchronisation is needed.
     */
    fun setGamepadButton(
        label: String,
        pressed: Boolean,
        send: (ByteArray) -> Unit,
    ): Boolean {
        val (index, mask) = GamepadReportFormat.BUTTON_MASKS[label] ?: return false
        val current = gamepadReport[index].toInt() and 0xFF
        val next = if (pressed) current or mask else current and mask.inv()
        gamepadReport[index] = next.toByte()
        send(gamepadReport)
        return true
    }

    /**
     * Set the hat-switch byte (0..7 = direction, 8 = neutral)
     * and emit. The composite report's hat occupies a single
     * byte; the canvas D-pad already produces values in the
     * right encoding.
     */
    fun setGamepadHat(
        hat: Int,
        send: (ByteArray) -> Unit,
    ) {
        gamepadReport[GamepadReportFormat.HAT_INDEX] = hat.toByte()
        send(gamepadReport)
    }

    private fun quantizeMouseDelta(
        delta: Float,
        isX: Boolean,
    ): Int {
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

    private fun publishTelemetry(telemetry: Telemetry) {
        val now = nowMs()
        if (lastTelemetryAtMs >= 0L && now - lastTelemetryAtMs < TELEMETRY_INTERVAL_MS) return
        lastTelemetryAtMs = now
        _telemetry.value = telemetry
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val TELEMETRY_INTERVAL_MS = 100L
    }
}

enum class HidMode { MOUSE, GAMEPAD }
data class Telemetry(
    val rawX: Int = 0,
    val rawY: Int = 0,
    val stickX: Int = 0,
    val stickY: Int = 0,
)
