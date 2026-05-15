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
        if (buttons == 0 && dx == 0 && dy == 0 && wheel == 0) return

        mode = HidMode.MOUSE
        if (!hasMouseReport) {
            mouseQueuedAtMs = queuedAtMs
        }
        hasMouseReport = true
        mouseButtons = buttons
        mouseDx += dx
        mouseDy += dy
        mouseWheel += wheel
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
            mouseButtons = 0
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
    }
}
