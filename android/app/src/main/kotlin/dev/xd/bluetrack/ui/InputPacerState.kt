package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal class InputPacerState(
    private val tickMs: Long = 8L,
    private val idleStopMs: Long = 120L,
    private val jankGapMs: Long = 24L,
    private val maxFrameDelta: Float = 14f,
    private val maxRecoveryFrames: Int = 6,
    private val epsilon: Float = 0.005f,
) {
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingMode = HidMode.MOUSE
    private var lastQueuedAtMs = 0L
    private var lastTickAtMs = 0L
    private var recoveryFrames = 0

    fun queue(dx: Float, dy: Float, mode: HidMode, nowMs: Long) {
        pendingDx += dx
        pendingDy += dy
        pendingMode = mode
        lastQueuedAtMs = nowMs
    }

    fun reset(mode: HidMode) {
        pendingDx = 0f
        pendingDy = 0f
        pendingMode = mode
        lastQueuedAtMs = 0L
        lastTickAtMs = 0L
        recoveryFrames = 0
    }

    fun nextFrame(nowMs: Long): InputPacerDecision {
        val dx = pendingDx
        val dy = pendingDy
        val hasMotion = abs(dx) > epsilon || abs(dy) > epsilon
        if (!hasMotion) {
            return if (nowMs - lastQueuedAtMs > idleStopMs) {
                lastTickAtMs = 0L
                recoveryFrames = 0
                InputPacerDecision.Stop
            } else {
                InputPacerDecision.Wait
            }
        }

        val gapMs = if (lastTickAtMs == 0L) tickMs else (nowMs - lastTickAtMs).coerceAtLeast(0L)
        lastTickAtMs = nowMs
        if (gapMs > jankGapMs) {
            val missedFrames = ((gapMs / tickMs).toInt() - 1).coerceIn(1, maxRecoveryFrames)
            recoveryFrames = max(recoveryFrames, missedFrames)
        }

        val magnitudeFrames = ceil(max(abs(dx), abs(dy)) / maxFrameDelta).toInt().coerceAtLeast(1)
        val frameCount = max(magnitudeFrames, recoveryFrames + 1)
        val frameDx = dx / frameCount
        val frameDy = dy / frameCount
        pendingDx -= frameDx
        pendingDy -= frameDy
        recoveryFrames = (recoveryFrames - 1).coerceAtLeast(0)

        return InputPacerDecision.Frame(
            dx = frameDx,
            dy = frameDy,
            mode = pendingMode,
            gapMs = gapMs,
            recoveryFramesRemaining = recoveryFrames,
        )
    }
}

internal sealed interface InputPacerDecision {
    data class Frame(
        val dx: Float,
        val dy: Float,
        val mode: HidMode,
        val gapMs: Long,
        val recoveryFramesRemaining: Int,
    ) : InputPacerDecision

    data object Wait : InputPacerDecision
    data object Stop : InputPacerDecision
}
