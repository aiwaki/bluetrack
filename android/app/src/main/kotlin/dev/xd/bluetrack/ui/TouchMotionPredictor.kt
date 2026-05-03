package dev.xd.bluetrack.ui

import kotlin.math.abs
import kotlin.math.sign

internal class TouchMotionPredictor(
    private val velocitySmoothing: Float = 0.62f,
    private val predictionGain: Float = 0.72f,
    private val holdMs: Long = 24L,
    private val horizonMs: Long = 72L,
    private val resetGapMs: Long = 180L,
    private val epsilon: Float = 0.005f,
) {
    private var lastTouchAtMs = -1L
    private var lastPredictionAtMs = -1L
    private var velocityX = 0f
    private var velocityY = 0f
    private var predictedDebtX = 0f
    private var predictedDebtY = 0f
    private var hasVelocity = false

    fun recordTouch(dx: Float, dy: Float, nowMs: Long): MotionDelta {
        val gapMs = if (lastTouchAtMs < 0L) -1L else nowMs - lastTouchAtMs
        val shouldReconcile = gapMs in 0..resetGapMs
        val adjusted = if (shouldReconcile) {
            MotionDelta(
                dx = reconcilePredictedAxis(dx, predictedDebtX),
                dy = reconcilePredictedAxis(dy, predictedDebtY),
            )
        } else {
            MotionDelta(dx, dy)
        }

        predictedDebtX = 0f
        predictedDebtY = 0f
        updateVelocity(dx, dy, gapMs)
        lastTouchAtMs = nowMs
        lastPredictionAtMs = nowMs
        return adjusted
    }

    fun predict(nowMs: Long): MotionDelta? {
        val touchAgeMs = nowMs - lastTouchAtMs
        val predictionStepMs = nowMs - lastPredictionAtMs
        if (!hasVelocity || touchAgeMs !in 1..horizonMs || predictionStepMs <= 0L) return null

        lastPredictionAtMs = nowMs
        val decay = predictionDecay(touchAgeMs)
        val dx = velocityX * predictionStepMs * predictionGain * decay
        val dy = velocityY * predictionStepMs * predictionGain * decay
        if (abs(dx) <= epsilon && abs(dy) <= epsilon) return null

        predictedDebtX += dx
        predictedDebtY += dy
        return MotionDelta(dx, dy)
    }

    fun reset() {
        lastTouchAtMs = -1L
        lastPredictionAtMs = -1L
        velocityX = 0f
        velocityY = 0f
        predictedDebtX = 0f
        predictedDebtY = 0f
        hasVelocity = false
    }

    private fun updateVelocity(dx: Float, dy: Float, gapMs: Long) {
        if (gapMs <= 0L || gapMs > resetGapMs) {
            hasVelocity = false
            velocityX = 0f
            velocityY = 0f
            return
        }

        val observedVelocityX = dx / gapMs
        val observedVelocityY = dy / gapMs
        if (hasVelocity) {
            velocityX = velocityX * (1f - velocitySmoothing) + observedVelocityX * velocitySmoothing
            velocityY = velocityY * (1f - velocitySmoothing) + observedVelocityY * velocitySmoothing
        } else {
            velocityX = observedVelocityX
            velocityY = observedVelocityY
            hasVelocity = true
        }
    }

    private fun predictionDecay(touchAgeMs: Long): Float {
        if (touchAgeMs <= holdMs) return 1f
        val fadeWindow = (horizonMs - holdMs).coerceAtLeast(1L)
        return (1f - (touchAgeMs - holdMs).toFloat() / fadeWindow).coerceIn(0f, 1f)
    }

    private fun reconcilePredictedAxis(actual: Float, predicted: Float): Float {
        if (abs(actual) <= epsilon || abs(predicted) <= epsilon) return actual
        if (actual.sign != predicted.sign) return actual

        val remaining = (abs(actual) - abs(predicted)).coerceAtLeast(0f)
        return actual.sign * remaining
    }

    data class MotionDelta(val dx: Float, val dy: Float)
}
