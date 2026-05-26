package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode

internal class HidOutputBuffer(
    private val maxGamepadReports: Int = 8,
) {
    private val lock = Any()
    private val gamepadReports = ArrayDeque<OutputFrame>()
    private var mode: HidMode? = null
    private var hasMouseReport = false
    private var mouseButtons = 0
    private var mouseDx = 0
    private var mouseDy = 0
    private var mouseWheel = 0
    private var mouseQueuedAtMs = 0L

    fun enqueue(
        mode: HidMode,
        report: ByteArray,
        queuedAtMs: Long,
    ) {
        synchronized(lock) {
            if (this.mode != null && this.mode != mode) {
                clearLocked()
            }
            when (mode) {
                HidMode.MOUSE -> enqueueMouse(report, queuedAtMs)
                HidMode.GAMEPAD -> enqueueGamepad(report, queuedAtMs)
            }
        }
    }

    fun poll(): OutputFrame? = synchronized(lock) {
        when (mode) {
            HidMode.MOUSE -> pollMouse()
            HidMode.GAMEPAD -> pollGamepad()
            null -> null
        }
    }

    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    fun hasPending(): Boolean = synchronized(lock) {
        hasMouseReport || gamepadReports.isNotEmpty()
    }

    private fun enqueueMouse(
        report: ByteArray,
        queuedAtMs: Long,
    ) {
        val buttons = report.getOrElse(0) { 0 }.toInt()
        val dx = report.getOrElse(1) { 0 }.toInt()
        val dy = report.getOrElse(2) { 0 }.toInt()
        val wheel = report.getOrElse(3) { 0 }.toInt()
        // Suppress idle noise but ALWAYS accept a button state
        // change — including the trailing release report
        // `[0,0,0,0]` after the user holds the touchpad button
        // and lifts. The earlier all-zero guard dropped that
        // release, so `mouseButtons` here stayed at 1 from the
        // press, the host never saw the up edge, and the
        // selection drag kept running once the user moved to
        // their real trackpad.
        val buttonsChanged = buttons != mouseButtons
        if (!buttonsChanged && dx == 0 && dy == 0 && wheel == 0) return

        mode = HidMode.MOUSE
        if (!hasMouseReport) {
            mouseQueuedAtMs = queuedAtMs
        }
        hasMouseReport = true
        mouseButtons = buttons
        mouseDx += dx
        mouseDy += dy
        mouseWheel += wheel
        // Cap accumulated wheel travel so a sender stall does
        // not let several pacer drains stack into a single
        // burst report — macOS reads big wheel values as
        // accelerated scroll and lurches the page.
        mouseWheel = mouseWheel.coerceIn(-MAX_WHEEL_PER_POLL, MAX_WHEEL_PER_POLL)
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

    private fun pollMouse(): OutputFrame? {
        if (!hasMouseReport) {
            mode = null
            return null
        }

        val dx = mouseDx.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        val dy = mouseDy.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        val wheel = mouseWheel.coerceIn(HID_MIN_DELTA, HID_MAX_DELTA)
        mouseDx -= dx
        mouseDy -= dy
        mouseWheel -= wheel

        val output =
            OutputFrame(
                mode = HidMode.MOUSE,
                report = byteArrayOf(mouseButtons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()),
                queuedAtMs = mouseQueuedAtMs,
            )
        if (mouseDx == 0 && mouseDy == 0 && mouseWheel == 0) {
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
        mouseQueuedAtMs = 0L
        gamepadReports.clear()
    }

    data class OutputFrame(
        val mode: HidMode,
        val report: ByteArray,
        val queuedAtMs: Long,
    )

    private companion object {
        const val HID_MIN_DELTA = -127
        const val HID_MAX_DELTA = 127
        const val MAX_WHEEL_PER_POLL = 2
    }
}
