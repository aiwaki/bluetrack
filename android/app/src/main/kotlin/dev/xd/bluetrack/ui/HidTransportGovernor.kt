package dev.xd.bluetrack.ui

import kotlin.math.max

internal class HidTransportGovernor(
    private val baseIntervalMs: Long = 6L,
    private val moderateBackoffIntervalMs: Long = 10L,
    private val severeBackoffIntervalMs: Long = 16L,
    private val moderateSendMs: Long = 12L,
    private val severeSendMs: Long = 48L,
    private val moderateBackoffWindowMs: Long = 500L,
    private val severeBackoffWindowMs: Long = 900L,
    private val fastRecoveryReports: Int = 12,
) {
    private var lastSendFinishedAtMs = -1L
    private var backoffUntilMs = -1L
    private var currentIntervalMs = baseIntervalMs
    private var fastReports = 0

    fun delayBeforeSend(nowMs: Long): Long {
        if (lastSendFinishedAtMs < 0L) return 0L
        return (lastSendFinishedAtMs + currentIntervalMs - nowMs).coerceAtLeast(0L)
    }

    fun recordSend(
        durationMs: Long,
        finishedAtMs: Long,
    ) {
        lastSendFinishedAtMs = finishedAtMs
        when {
            durationMs >= severeSendMs -> {
                currentIntervalMs = severeBackoffIntervalMs
                backoffUntilMs = finishedAtMs + severeBackoffWindowMs
                fastReports = 0
            }
            durationMs >= moderateSendMs -> {
                currentIntervalMs = max(currentIntervalMs, moderateBackoffIntervalMs)
                backoffUntilMs = max(backoffUntilMs, finishedAtMs + moderateBackoffWindowMs)
                fastReports = 0
            }
            finishedAtMs >= backoffUntilMs && currentIntervalMs > baseIntervalMs -> {
                fastReports += 1
                if (fastReports >= fastRecoveryReports) {
                    currentIntervalMs = baseIntervalMs
                    fastReports = 0
                }
            }
            else -> {
                fastReports = 0
            }
        }
    }

    fun reset() {
        lastSendFinishedAtMs = -1L
        backoffUntilMs = -1L
        currentIntervalMs = baseIntervalMs
        fastReports = 0
    }
}
