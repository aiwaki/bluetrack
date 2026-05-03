package dev.xd.bluetrack.ui

import android.util.Log

internal class InputDiagnostics(
    private val logger: (String) -> Unit = { message -> Log.w(TAG, message) },
    private val touchGapWarningMs: Long = 28L,
    private val pacerGapWarningMs: Long = 24L,
    private val queueLatencyWarningMs: Long = 18L,
    private val hidSendWarningMs: Long = 10L,
    private val warningCooldownMs: Long = 1500L,
) {
    private var lastTouchAtMs = -1L
    private var lastPacerAtMs = -1L
    private var lastWarningAtMs = Long.MIN_VALUE / 2
    private var touchGapWarnings = 0
    private var pacerGapWarnings = 0
    private var queueLatencyWarnings = 0
    private var hidSendWarnings = 0
    private var maxTouchGapMs = 0L
    private var maxPacerGapMs = 0L
    private var maxQueueLatencyMs = 0L
    private var maxHidSendMs = 0L
    private var frames = 0

    fun recordTouch(nowMs: Long) {
        val gap = if (lastTouchAtMs < 0L) 0L else nowMs - lastTouchAtMs
        lastTouchAtMs = nowMs
        if (gap > maxTouchGapMs) maxTouchGapMs = gap
        if (gap > touchGapWarningMs) {
            touchGapWarnings += 1
            maybeLog(nowMs, "touch gap ${gap}ms")
        }
    }

    fun recordPacerTick(nowMs: Long) {
        val gap = if (lastPacerAtMs < 0L) 0L else nowMs - lastPacerAtMs
        lastPacerAtMs = nowMs
        if (gap > maxPacerGapMs) maxPacerGapMs = gap
        if (gap > pacerGapWarningMs) {
            pacerGapWarnings += 1
            maybeLog(nowMs, "pacer gap ${gap}ms")
        }
    }

    fun recordFrame(nowMs: Long, queuedAtMs: Long) {
        frames += 1
        val latency = (nowMs - queuedAtMs).coerceAtLeast(0L)
        if (latency > maxQueueLatencyMs) maxQueueLatencyMs = latency
        if (latency > queueLatencyWarningMs) {
            queueLatencyWarnings += 1
            maybeLog(nowMs, "queue latency ${latency}ms")
        }
    }

    fun recordHidSend(durationNs: Long, nowMs: Long) {
        val durationMs = durationNs / NANOS_PER_MS
        if (durationMs > maxHidSendMs) maxHidSendMs = durationMs
        if (durationMs > hidSendWarningMs) {
            hidSendWarnings += 1
            maybeLog(nowMs, "HID send ${durationMs}ms")
        }
    }

    fun resetPacerClock() {
        lastPacerAtMs = -1L
    }

    fun resetTouchClock() {
        lastTouchAtMs = -1L
    }

    fun snapshot(): InputDiagnosticsSnapshot = InputDiagnosticsSnapshot(
        frames = frames,
        touchGapWarnings = touchGapWarnings,
        pacerGapWarnings = pacerGapWarnings,
        queueLatencyWarnings = queueLatencyWarnings,
        hidSendWarnings = hidSendWarnings,
        maxTouchGapMs = maxTouchGapMs,
        maxPacerGapMs = maxPacerGapMs,
        maxQueueLatencyMs = maxQueueLatencyMs,
        maxHidSendMs = maxHidSendMs,
    )

    private fun maybeLog(nowMs: Long, reason: String) {
        if (nowMs - lastWarningAtMs < warningCooldownMs) return
        lastWarningAtMs = nowMs
        val snapshot = snapshot()
        logger(
            "Input jank: $reason; frames=${snapshot.frames}; maxTouchGap=${snapshot.maxTouchGapMs}ms; " +
                "maxPacerGap=${snapshot.maxPacerGapMs}ms; maxQueueLatency=${snapshot.maxQueueLatencyMs}ms; " +
                "maxHidSend=${snapshot.maxHidSendMs}ms; warnings=" +
                "touch:${snapshot.touchGapWarnings},pacer:${snapshot.pacerGapWarnings}," +
                "queue:${snapshot.queueLatencyWarnings},hid:${snapshot.hidSendWarnings}"
        )
    }

    private companion object {
        const val TAG = "BluetrackInput"
        const val NANOS_PER_MS = 1_000_000L
    }
}

internal data class InputDiagnosticsSnapshot(
    val frames: Int,
    val touchGapWarnings: Int,
    val pacerGapWarnings: Int,
    val queueLatencyWarnings: Int,
    val hidSendWarnings: Int,
    val maxTouchGapMs: Long,
    val maxPacerGapMs: Long,
    val maxQueueLatencyMs: Long,
    val maxHidSendMs: Long,
)
