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

    // Mouse report layout matches the descriptor in BleHidGateway:
    //   [0] buttons (low 3 bits + 5 padding)
    //   [1] X delta (signed 8-bit)
    //   [2] Y delta (signed 8-bit)
    //   [3] Wheel (signed 8-bit) — Usage 0x38 is already declared
    //       in the descriptor, no re-pair needed to start writing
    //       to it.
    private val mouseReport = byteArrayOf(0, 0, 0, 0)
    private var mouseCarryX = 0f
    private var mouseCarryY = 0f
    private var wheelCarryY = 0f

    // Latched mouse button bits (L=1, R=2, M=4) mirrored into
    // `mouseReport[0]` on every emit so motion / wheel frames
    // never accidentally release a held button.
    @Volatile private var mouseButtons: Int = 0
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
            mouseReport[0] = (mouseButtons and 0x07).toByte()
            mouseReport[1] = mouseX.toByte()
            mouseReport[2] = mouseY.toByte()
            // Wheel byte is reset on every motion frame so a stale
            // value from the last 2-finger scroll never lingers
            // into a 1-finger drag.
            mouseReport[3] = 0
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

    /**
     * Emit a wheel-only mouse report. Used by the touchpad
     * two-finger scroll gesture: positive `dy` scrolls up (matches
     * the Windows HID convention `wheel > 0 ⇒ away from user`).
     * Caller passes a pre-scaled delta in wheel units (typically
     * `-touchDy / WHEEL_PIXELS_PER_TICK`); fractional residue is
     * carried across calls so slow drags still emit clean unit
     * ticks instead of dropping below the integer floor.
     *
     * Mouse-mode only — invoked from the input pacer alongside
     * `processMouseToStick`. The X/Y bytes are zeroed so the host
     * sees a pure scroll event with no cursor displacement.
     */
    fun processWheel(
        dy: Float,
        send: (ByteArray) -> Unit,
    ) {
        val carried = dy + wheelCarryY
        // Cap per-emit wheel travel to keep scroll smooth on
        // hosts that interpret each integer as one wheel notch.
        // The full HID range is ±127, but anything past ~3 in a
        // single report triggers macOS's accelerated-scroll
        // heuristic and the screen lurches. Residual travel
        // beyond the cap stays in `wheelCarryY` and emits on the
        // next pacer tick, so total scroll distance is preserved
        // — just spread out as several small notches instead of
        // one big jump.
        val whole = carried.roundToInt().coerceIn(-MAX_WHEEL_PER_EMIT, MAX_WHEEL_PER_EMIT)
        wheelCarryY = carried - whole
        if (whole == 0) return
        mouseReport[0] = (mouseButtons and 0x07).toByte()
        mouseReport[1] = 0
        mouseReport[2] = 0
        mouseReport[3] = whole.toByte()
        send(mouseReport)
    }

    /**
     * Set or clear a mouse button. [buttonMask] uses HID button
     * bit values: `1 = left`, `2 = right`, `4 = middle` (matches
     * Android's `MotionEvent.BUTTON_PRIMARY/SECONDARY/TERTIARY`,
     * so the mirror surface can pass the value through unchanged).
     * Emits a fresh report immediately with zero X/Y/Wheel so the
     * host sees a clean button event without a phantom cursor
     * step. Subsequent motion/wheel frames keep the latched bits
     * via `mouseReport[0] = mouseButtons` so a held drag works.
     */
    fun setMouseButton(
        buttonMask: Int,
        pressed: Boolean,
        send: (ByteArray) -> Unit,
    ) {
        val mask = buttonMask and 0x07
        if (mask == 0) return
        mouseButtons =
            if (pressed) {
                mouseButtons or mask
            } else {
                mouseButtons and mask.inv()
            }
        mouseReport[0] = (mouseButtons and 0x07).toByte()
        mouseReport[1] = 0
        mouseReport[2] = 0
        mouseReport[3] = 0
        send(mouseReport)
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
        const val MAX_WHEEL_PER_EMIT = 1
    }
}

enum class HidMode { MOUSE, GAMEPAD }
data class Telemetry(
    val rawX: Int = 0,
    val rawY: Int = 0,
    val stickX: Int = 0,
    val stickY: Int = 0,
)
