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
    //   [3] Wheel vertical (signed 8-bit) — Usage 0x38
    //   [4] Wheel horizontal — AC Pan (signed 8-bit). NEW byte;
    //       requires the re-paired descriptor that declares the
    //       Consumer-page AC Pan usage.
    private val mouseReport = byteArrayOf(0, 0, 0, 0, 0)
    private var mouseCarryX = 0f
    private var mouseCarryY = 0f
    private var wheelCarryY = 0f
    private var wheelCarryX = 0f

    // Keyboard report (boot protocol, report ID 3): [0] modifier
    // bitmask, [1] reserved, [2..7] up to 6 simultaneous keycodes
    // (HID Usage page 0x07). Driven by the Mac-trackpad gesture
    // handlers (pinch zoom, 3/4-finger swipes) which map to
    // Cmd/Ctrl/F-key chords the host's shortcut hooks honour.
    private val keyboardReport = ByteArray(8)
    private var keyboardModifiers = 0
    private val keycodesPressed = LinkedHashSet<Int>()

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
            // Wheel bytes reset on every motion frame so a stale
            // value from the last 2-finger scroll never lingers
            // into a 1-finger drag.
            mouseReport[3] = 0
            mouseReport[4] = 0
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
        dx: Float,
        send: (ByteArray) -> Unit,
    ) {
        val carriedY = dy + wheelCarryY
        val carriedX = dx + wheelCarryX
        // Cap per-emit wheel travel per axis to keep scroll smooth
        // on hosts that interpret each integer as one wheel notch.
        // The full HID range is ±127, but anything past ~3 in a
        // single report triggers macOS's accelerated-scroll
        // heuristic and the screen lurches. Residual travel beyond
        // the cap stays in the per-axis carry and emits on the next
        // pacer tick, so total scroll distance is preserved — just
        // spread out as several small notches instead of one jump.
        val wholeY = carriedY.roundToInt().coerceIn(-MAX_WHEEL_PER_EMIT, MAX_WHEEL_PER_EMIT)
        val wholeX = carriedX.roundToInt().coerceIn(-MAX_WHEEL_PER_EMIT, MAX_WHEEL_PER_EMIT)
        wheelCarryY = carriedY - wholeY
        wheelCarryX = carriedX - wholeX
        if (wholeY == 0 && wholeX == 0) return
        mouseReport[0] = (mouseButtons and 0x07).toByte()
        mouseReport[1] = 0
        mouseReport[2] = 0
        mouseReport[3] = wholeY.toByte()
        mouseReport[4] = wholeX.toByte()
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
        mouseReport[4] = 0
        send(mouseReport)
    }

    /**
     * A no-op mouse report preserving the currently-latched button bits
     * with zero motion/wheel. Sent on a low-rate idle ticker to keep the
     * BR/EDR link out of deep sniff so the first real input after an idle
     * pause does not pay the sniff-wake latency (~hundreds of ms). Re-
     * sending the held button state is idempotent on the host, so a
     * keepalive fired mid hold-drag never releases the drag.
     */
    fun keepaliveReport(): ByteArray = byteArrayOf((mouseButtons and 0x07).toByte(), 0, 0, 0, 0)

    /**
     * Press one or more keyboard keys. [modifier] is an OR of the
     * `HidKeys.MOD_*` bitmasks (held in the report's modifier byte
     * until [processKeyUp] clears them); [keycode] is a HID Usage
     * page 0x07 code, or 0 for a modifier-only chord. Up to 6
     * keycodes roll over simultaneously (boot-keyboard limit);
     * extra presses past 6 are dropped until a key releases.
     */
    fun processKeyDown(
        modifier: Int,
        keycode: Int,
        send: (ByteArray) -> Unit,
    ) {
        keyboardModifiers = keyboardModifiers or modifier
        if (keycode == 0) {
            emitKeyboard(send)
        } else if (keycodesPressed.size < MAX_KEYCODES && keycodesPressed.add(keycode)) {
            emitKeyboard(send)
        }
    }

    /**
     * Release [keycode] and clear [modifier] bits, then emit the
     * updated report. Releasing a key not currently down still
     * emits (harmless idempotent refresh).
     */
    fun processKeyUp(
        modifier: Int,
        keycode: Int,
        send: (ByteArray) -> Unit,
    ) {
        keyboardModifiers = keyboardModifiers and modifier.inv()
        keycodesPressed.remove(keycode)
        emitKeyboard(send)
    }

    /**
     * Fire a complete key chord: down then immediately up. Used by
     * the Mac-trackpad gesture handlers, which map each gesture to
     * one discrete shortcut keystroke (e.g. Cmd+= zoom, F3 Mission
     * Control).
     */
    fun tapKey(
        modifier: Int,
        keycode: Int,
        send: (ByteArray) -> Unit,
    ) {
        processKeyDown(modifier, keycode, send)
        processKeyUp(modifier, keycode, send)
    }

    private fun emitKeyboard(send: (ByteArray) -> Unit) {
        keyboardReport[0] = (keyboardModifiers and 0xFF).toByte()
        keyboardReport[1] = 0
        var i = 2
        for (kc in keycodesPressed) {
            if (i >= keyboardReport.size) break
            keyboardReport[i++] = kc.toByte()
        }
        while (i < keyboardReport.size) keyboardReport[i++] = 0
        send(keyboardReport)
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
        const val MAX_KEYCODES = 6
    }
}

enum class HidMode { MOUSE, GAMEPAD, KEYBOARD }

/**
 * HID Usage page 0x07 keycodes + modifier bitmasks used by the
 * Mac-trackpad gesture → keyboard-chord mappings (pinch zoom,
 * 3/4-finger swipes). Kept public so [TranslationEngine.tapKey]
 * callers (gesture handlers) and unit tests share one source.
 */
object HidKeys {
    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_LGUI = 0x08

    const val KC_F3 = 0x3C
    const val KC_F4 = 0x3D
    const val KC_F11 = 0x44
    const val KC_RIGHT = 0x4F
    const val KC_LEFT = 0x50
    const val KC_DOWN = 0x51
    const val KC_UP = 0x52
    const val KC_EQUAL = 0x2E
    const val KC_MINUS = 0x2D
}
data class Telemetry(
    val rawX: Int = 0,
    val rawY: Int = 0,
    val stickX: Int = 0,
    val stickY: Int = 0,
)
