package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidKeys
import dev.xd.bluetrack.ui.TouchGestureClassifier.KeyChord
import dev.xd.bluetrack.ui.TouchGestureClassifier.TwoFingerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureClassifierTest {
    // --- 2-finger pinch vs scroll disambiguation ---

    @Test
    fun latchesPinchWhenSeparationChangeDominatesVerticalTravel() {
        // |distChange| (40) > |avgYChange| (10) * 1.5 = 15 → PINCH.
        assertEquals(
            TwoFingerMode.PINCH,
            TouchGestureClassifier.classifyTwoFinger(distChangePx = 40f, avgYChangePx = 10f),
        )
    }

    @Test
    fun latchesScrollWhenVerticalTravelDominates() {
        // |distChange| (10) is not > |avgYChange| (30) * 1.5 = 45 → SCROLL.
        assertEquals(
            TwoFingerMode.SCROLL,
            TouchGestureClassifier.classifyTwoFinger(distChangePx = 10f, avgYChangePx = 30f),
        )
    }

    @Test
    fun givesScrollPrecedenceOnNearPureVerticalMove() {
        // Pure vertical: distance barely changes, fingers slide together.
        assertEquals(
            TwoFingerMode.SCROLL,
            TouchGestureClassifier.classifyTwoFinger(distChangePx = 2f, avgYChangePx = 50f),
        )
    }

    // --- pinch notch counting ---

    @Test
    fun countsOneSpreadNotchAndAdvancesAnchor() {
        val r = TouchGestureClassifier.pinchNotches(currentDist = 240f, anchorDist = 200f, notchPx = 36f)
        assertEquals(1, r.count)
        assertEquals(236f, r.newAnchorDist)
    }

    @Test
    fun countsNegativePinchNotch() {
        val r = TouchGestureClassifier.pinchNotches(currentDist = 160f, anchorDist = 200f, notchPx = 36f)
        assertEquals(-1, r.count)
        assertEquals(164f, r.newAnchorDist)
    }

    @Test
    fun crossingMultipleNotchesAtOnceEmitsEach() {
        val r = TouchGestureClassifier.pinchNotches(currentDist = 290f, anchorDist = 200f, notchPx = 36f)
        assertEquals(2, r.count)
        assertEquals(272f, r.newAnchorDist)
    }

    @Test
    fun subNotchMoveEmitsNothingAndKeepsAnchor() {
        val r = TouchGestureClassifier.pinchNotches(currentDist = 220f, anchorDist = 200f, notchPx = 36f)
        assertEquals(0, r.count)
        assertEquals(200f, r.newAnchorDist)
    }

    // --- zoom chord mapping ---

    @Test
    fun spreadMapsToCmdEqualAndPinchToCmdMinus() {
        assertEquals(KeyChord(HidKeys.MOD_LGUI, HidKeys.KC_EQUAL), TouchGestureClassifier.zoomChord(1))
        assertEquals(KeyChord(HidKeys.MOD_LGUI, HidKeys.KC_MINUS), TouchGestureClassifier.zoomChord(-1))
        assertNull(TouchGestureClassifier.zoomChord(0))
    }

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
