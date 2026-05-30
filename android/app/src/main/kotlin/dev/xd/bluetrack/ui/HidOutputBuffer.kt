package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode

internal class HidOutputBuffer(
    private val maxGamepadReports: Int = 8,
    private val nowMsProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val lock = Any()
    private val gamepadReports = ArrayDeque<OutputFrame>()
    private var mode: HidMode? = null
    private var hasMouseReport = false
    private var mouseButtons = 0
    private var mouseDx = 0
    private var mouseDy = 0
    private var mouseWheel = 0
    private var mouseWheelX = 0
    private var mouseQueuedAtMs = 0L

    // Keyboard frames ride their own pass-through FIFO queue: enqueueing
    // one never triggers the mouse/gamepad coalesce-or-clear path, so a
    // gesture-driven key chord can't wipe pending cursor motion. Drained
    // first in poll().
    private val keyboardReports = ArrayDeque<OutputFrame>()

    fun enqueue(
        mode: HidMode,
        report: ByteArray,
        queuedAtMs: Long,
    ) {
        synchronized(lock) {
            // Keyboard is orthogonal: queue it without touching `mode`
            // or the mouse/gamepad coalesce-clear below.
            if (mode == HidMode.KEYBOARD) {
                enqueueKeyboard(report, queuedAtMs)
                return@synchronized
            }
            if (this.mode != null && this.mode != mode) {
                clearLocked()
            }
            when (mode) {
                HidMode.MOUSE -> enqueueMouse(report, queuedAtMs)
                HidMode.GAMEPAD -> enqueueGamepad(report, queuedAtMs)
                HidMode.KEYBOARD -> {} // handled above
            }
        }
    }

    fun poll(): OutputFrame? = synchronized(lock) {
        // Keyboard frames drain first, ahead of the active mouse/gamepad
        // path, so a gesture chord lands promptly without waiting on a
        // motion backlog.
        keyboardReports.removeFirstOrNull()?.let { return@synchronized it }
        when (mode) {
            HidMode.MOUSE -> pollMouse()
            HidMode.GAMEPAD -> pollGamepad()
            HidMode.KEYBOARD -> null
            null -> null
        }
    }

    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    fun hasPending(): Boolean = synchronized(lock) {
        hasMouseReport || gamepadReports.isNotEmpty() || keyboardReports.isNotEmpty()
    }

    private fun enqueueMouse(
        report: ByteArray,
        queuedAtMs: Long,
    ) {
        val buttons = report.getOrElse(0) { 0 }.toInt()
        val dx = report.getOrElse(1) { 0 }.toInt()
        val dy = report.getOrElse(2) { 0 }.toInt()
        val wheel = report.getOrElse(3) { 0 }.toInt()
        val wheelX = report.getOrElse(4) { 0 }.toInt()
        // Suppress idle noise but ALWAYS accept a button state
        // change — including the trailing release report
        // `[0,0,0,0]` after the user holds the touchpad button
        // and lifts. The earlier all-zero guard dropped that
        // release, so `mouseButtons` here stayed at 1 from the
        // press, the host never saw the up edge, and the
        // selection drag kept running once the user moved to
        // their real trackpad.
        val buttonsChanged = buttons != mouseButtons
        if (!buttonsChanged && dx == 0 && dy == 0 && wheel == 0 && wheelX == 0) return

        mode = HidMode.MOUSE
        // Drop accumulated motion if the buffered frame is older
        // than STALE_MOTION_MS — happens when the Bluetooth radio
        // stalled for hundreds of ms (sendReport blocked) and the
        // input pacer kept adding deltas. Stale deltas no longer
        // reflect the user's current finger position, so flushing
        // them as a burst when the radio recovers makes the cursor
        // lurch. Reset to fresh deltas; button state survives.
        if (hasMouseReport && nowMsProvider() - mouseQueuedAtMs > STALE_MOTION_MS) {
            mouseDx = 0
            mouseDy = 0
            mouseWheel = 0
            mouseWheelX = 0
        }
        if (!hasMouseReport) {
            mouseQueuedAtMs = queuedAtMs
        } else if (nowMsProvider() - mouseQueuedAtMs > STALE_MOTION_MS) {
            // After drop above, re-anchor the queued-at timestamp
            // so the staleness window restarts from the incoming
            // delta.
            mouseQueuedAtMs = queuedAtMs
        }
        hasMouseReport = true
        mouseButtons = buttons
        mouseDx += dx
        mouseDy += dy
        mouseWheel += wheel
        mouseWheelX += wheelX
        // Cap accumulated cursor backlog so a sender stall does
        // not let dozens of pacer drains stack into a single
        // burst when the link recovers — the user's intent for
        // motion >24 ms old is already irrelevant. ±48 px covers
        // ~24 ms of brisk swipe motion at typical Bluetrack
        // sensitivity; anything beyond that is "queued lurch"
        // territory.
        mouseDx = mouseDx.coerceIn(-MAX_MOTION_PER_POLL, MAX_MOTION_PER_POLL)
        mouseDy = mouseDy.coerceIn(-MAX_MOTION_PER_POLL, MAX_MOTION_PER_POLL)
        // Buffer cap separates from PER-EMIT cap. Per-emit stays
        // at ±1 wheel notch (TranslationEngine.MAX_WHEEL_PER_EMIT)
        // so macOS's accelerated-scroll heuristic never trips.
        // The buffer cap here only bounds how many notches can
        // sit pending between pacer drains so a fast finger
        // flick keeps its momentum in the queue and the next
        // few 8 ms drains can flush them as a clean stream
        // instead of dropping remainder above the cap. ±2 was
        // too tight — at 8 ms drain × 1 emit/drain it capped
        // throughput at 125 wheel/sec, well below a brisk Mac
        // trackpad flick (~250-400 wheel/sec equiv). ±8 gives
        // 32 ms of headroom — long enough to ride out a sender
        // stall but short enough that a long held-press doesn't
        // queue absurd amounts of pending scroll.
        mouseWheel = mouseWheel.coerceIn(-MAX_WHEEL_PER_POLL, MAX_WHEEL_PER_POLL)
        mouseWheelX = mouseWheelX.coerceIn(-MAX_WHEEL_PER_POLL, MAX_WHEEL_PER_POLL)
    }

    private fun enqueueGamepad(
        report: ByteArray,
        queuedAtMs: Long,
    ) {
        mode = HidMode.GAMEPAD
        if (gamepadReports.size >= maxGamepadReports) {
            gamepadReports.removeFirst()
        }
        gamepadReports.addLast(OutputFrame(HidMode.GAMEPAD, report.copyOf(), queuedAtMs))
    }

    private fun enqueueKeyboard(
        report: ByteArray,
        queuedAtMs: Long,
    ) {
        // Bounded like the gamepad queue so a key-event flood can't grow
        // the backlog without limit; oldest frame drops first.
        if (keyboardReports.size >= maxGamepadReports) {
            keyboardReports.removeFirst()
        }
        keyboardReports.addLast(OutputFrame(HidMode.KEYBOARD, report.copyOf(), queuedAtMs))
    }

    private fun pollMouse(): OutputFrame? {
        if (!hasMouseReport) {
            mode = null
            return null
        }

        val dx = mouseDx.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        val dy = mouseDy.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        val wheel = mouseWheel.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        val wheelX = mouseWheelX.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        mouseDx -= dx
        mouseDy -= dy
        mouseWheel -= wheel
        mouseWheelX -= wheelX

        val output =
            OutputFrame(
                mode = HidMode.MOUSE,
                report =
                    byteArrayOf(
                        mouseButtons.toByte(),
                        dx.toByte(),
                        dy.toByte(),
                        wheel.toByte(),
                        wheelX.toByte(),
                    ),
                queuedAtMs = mouseQueuedAtMs,
            )
        if (mouseDx == 0 && mouseDy == 0 && mouseWheel == 0 && mouseWheelX == 0) {
            hasMouseReport = false
            // Do NOT reset `mouseButtons` here. The field tracks
            // the LAST button state the buffer emitted to the
            // host, and the `enqueueMouse` change-detector
            // compares incoming reports against it. Resetting to
            // 0 every drain made the trailing release report
            // `[0,0,0,0]` look like a no-op (because the buffer
            // believed it had already cleared the bit), so the
            // host never saw the up edge after a tap or hold.
            // The bit only legitimately clears via a release
            // report — or `clearLocked()` on mode switch.
            mode = null
        }
        return output
    }

    private fun pollGamepad(): OutputFrame? {
        val output = gamepadReports.removeFirstOrNull()
        if (gamepadReports.isEmpty()) {
            mode = null
        }
        return output
    }

    private fun clearLocked() {
        mode = null
        hasMouseReport = false
        mouseButtons = 0
        mouseDx = 0
        mouseDy = 0
        mouseWheel = 0
        mouseWheelX = 0
        mouseQueuedAtMs = 0L
        gamepadReports.clear()
        keyboardReports.clear()
    }

    data class OutputFrame(
        val mode: HidMode,
        val report: ByteArray,
        val queuedAtMs: Long,
    )

    private companion object {
        const val HID_MIN_DELTA = -127
        const val HID_MAX_DELTA = 127
        const val MAX_WHEEL_PER_POLL = 8
        const val MAX_MOTION_PER_POLL = 48
        const val STALE_MOTION_MS = 100L
    }
}
