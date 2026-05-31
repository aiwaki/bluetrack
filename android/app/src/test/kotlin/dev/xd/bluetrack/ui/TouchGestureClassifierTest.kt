package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidKeys
import dev.xd.bluetrack.ui.TouchGestureClassifier.KeyChord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureClassifierTest {
    // --- 3-finger swipe ---

    @Test
    fun threeFingerSwipeRightMapsToCtrlRight() {
        assertEquals(
            KeyChord(HidKeys.MOD_LCTRL, HidKeys.KC_RIGHT),
            TouchGestureClassifier.threeFingerSwipe(dxPx = 120f, dyPx = 10f, thresholdPx = 80f),
        )
    }

    @Test
    fun threeFingerSwipeLeftMapsToCtrlLeft() {
        assertEquals(
            KeyChord(HidKeys.MOD_LCTRL, HidKeys.KC_LEFT),
            TouchGestureClassifier.threeFingerSwipe(dxPx = -120f, dyPx = -10f, thresholdPx = 80f),
        )
    }

    @Test
    fun threeFingerSwipeUpMapsToBareF3() {
        // Swipe up = negative dy = Mission Control = F3 with no modifier.
        assertEquals(
            KeyChord(0, HidKeys.KC_F3),
            TouchGestureClassifier.threeFingerSwipe(dxPx = 5f, dyPx = -120f, thresholdPx = 80f),
        )
    }

    @Test
    fun threeFingerSwipeDownMapsToCtrlDown() {
        assertEquals(
            KeyChord(HidKeys.MOD_LCTRL, HidKeys.KC_DOWN),
            TouchGestureClassifier.threeFingerSwipe(dxPx = 5f, dyPx = 120f, thresholdPx = 80f),
        )
    }

    @Test
    fun threeFingerSwipeBelowThresholdEmitsNothing() {
        assertNull(TouchGestureClassifier.threeFingerSwipe(dxPx = 40f, dyPx = 30f, thresholdPx = 80f))
    }

    // --- 4-finger pinch/spread ---

    @Test
    fun fourFingerPinchInMapsToF4Launchpad() {
        assertEquals(
            KeyChord(0, HidKeys.KC_F4),
            TouchGestureClassifier.fourFingerZoom(distDeltaPx = -90f, notchPx = 80f),
        )
    }

    @Test
    fun fourFingerSpreadOutMapsToF11ShowDesktop() {
        assertEquals(
            KeyChord(0, HidKeys.KC_F11),
            TouchGestureClassifier.fourFingerZoom(distDeltaPx = 90f, notchPx = 80f),
        )
    }

    @Test
    fun fourFingerBelowNotchEmitsNothing() {
        assertNull(TouchGestureClassifier.fourFingerZoom(distDeltaPx = 40f, notchPx = 80f))
    }

    // --- palm-slap reject ---

    @Test
    fun palmSlapDetectedOnInstantJumpToFourWithoutThree() {
        assertTrue(
            TouchGestureClassifier.isPalmSlap(
                prevPointerCount = 1,
                newPointerCount = 4,
                sawThreePointers = false,
                elapsedMs = 40L,
            ),
        )
    }

    @Test
    fun rampThroughThreeIsNotPalmSlap() {
        assertFalse(
            TouchGestureClassifier.isPalmSlap(
                prevPointerCount = 1,
                newPointerCount = 4,
                sawThreePointers = true,
                elapsedMs = 40L,
            ),
        )
    }

    @Test
    fun slowSettleToFourIsNotPalmSlap() {
        assertFalse(
            TouchGestureClassifier.isPalmSlap(
                prevPointerCount = 1,
                newPointerCount = 4,
                sawThreePointers = false,
                elapsedMs = 120L,
            ),
        )
    }
}
