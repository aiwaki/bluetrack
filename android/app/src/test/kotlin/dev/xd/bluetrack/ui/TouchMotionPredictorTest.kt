package dev.xd.bluetrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMotionPredictorTest {
    @Test
    fun predictsShortTouchGapsFromRecentVelocity() {
        val predictor = TouchMotionPredictor(predictionGain = 0.72f)

        predictor.recordTouch(dx = 8f, dy = 0f, nowMs = 0L)
        predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 16L)

        val predicted = requireNotNull(predictor.predict(nowMs = 24L))

        assertEquals(5.76f, predicted.dx, 0.001f)
        assertEquals(0f, predicted.dy, 0.001f)
    }

    @Test
    fun reconcilesPredictedMotionWithNextRealTouch() {
        val predictor = TouchMotionPredictor(predictionGain = 1f)

        predictor.recordTouch(dx = 8f, dy = 0f, nowMs = 0L)
        predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 16L)
        predictor.predict(nowMs = 24L)

        val adjusted = predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 32L)

        assertEquals(8f, adjusted.dx, 0.001f)
        assertEquals(0f, adjusted.dy, 0.001f)
    }

    @Test
    fun doesNotReverseMovementWhenPredictionOvershootsActual() {
        val predictor = TouchMotionPredictor(predictionGain = 1f)

        predictor.recordTouch(dx = 8f, dy = 0f, nowMs = 0L)
        predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 16L)
        predictor.predict(nowMs = 40L)

        val adjusted = predictor.recordTouch(dx = 4f, dy = 0f, nowMs = 48L)

        assertEquals(0f, adjusted.dx, 0.001f)
        assertEquals(0f, adjusted.dy, 0.001f)
    }

    @Test
    fun leavesDirectionChangesUntouched() {
        val predictor = TouchMotionPredictor(predictionGain = 1f)

        predictor.recordTouch(dx = 8f, dy = 0f, nowMs = 0L)
        predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 16L)
        predictor.predict(nowMs = 24L)

        val adjusted = predictor.recordTouch(dx = -12f, dy = 0f, nowMs = 32L)

        assertEquals(-12f, adjusted.dx, 0.001f)
    }

    @Test
    fun stopsPredictingAfterHorizon() {
        val predictor = TouchMotionPredictor(horizonMs = 72L)

        predictor.recordTouch(dx = 8f, dy = 0f, nowMs = 0L)
        predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 16L)

        assertNull(predictor.predict(nowMs = 100L))
    }

    @Test
    fun resetClearsVelocity() {
        val predictor = TouchMotionPredictor()

        predictor.recordTouch(dx = 8f, dy = 0f, nowMs = 0L)
        predictor.recordTouch(dx = 16f, dy = 0f, nowMs = 16L)
        predictor.reset()

        assertNull(predictor.predict(nowMs = 24L))
    }
}
