package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidKeys
import kotlin.math.abs

/**
 * Pure, Android-free classification of Mac-trackpad multi-touch
 * gestures into HID intents (a horizontal-scroll axis, zoom/desktop
 * keyboard chords). The stateful tracking — pointer separation,
 * velocity, gesture latches, palm-window timestamps — lives in
 * `MainActivity`'s touch listener; this object owns only the
 * decision math so it can carry JVM unit tests, mirroring the
 * `GatewayStatusReducer` / `BatteryNotifyPolicy` split.
 *
 * All distances arrive in pixels: the caller converts the plan's
 * dp tunables ([PINCH_NOTCH_DP], [SWIPE_THRESHOLD_DP],
 * [FOUR_FINGER_NOTCH_DP]) with the live display density so the
 * gesture feel is screen-independent.
 */
object TouchGestureClassifier {
    /** What a 2-finger drag latched into on its first MOVE. */
    enum class TwoFingerMode { SCROLL, PINCH }

    /**
     * A keyboard chord to fire via `MainViewModel.tapHidKey`.
     * [modifier] is an OR of `HidKeys.MOD_*` (0 = no modifier);
     * [keycode] is a HID Usage page 0x07 code.
     */
    data class KeyChord(
        val modifier: Int,
        val keycode: Int,
    )

    /** Outcome of a pinch-distance notch evaluation. */
    data class PinchNotches(
        val count: Int,
        val newAnchorDist: Float,
    )

    /**
     * Disambiguate the first 2-finger MOVE after the second finger
     * lands. Latch PINCH when the change in pointer separation
     * exceeds the shared vertical travel by [ratio]×; otherwise
     * SCROLL. Near-pure-vertical motion therefore stays scroll,
     * giving scroll precedence (the common case). Sticky until
     * ACTION_UP — the caller does not re-evaluate.
     */
    fun classifyTwoFinger(
        distChangePx: Float,
        avgYChangePx: Float,
        ratio: Float = PINCH_VS_SCROLL_RATIO,
    ): TwoFingerMode =
        if (abs(distChangePx) > abs(avgYChangePx) * ratio) {
            TwoFingerMode.PINCH
        } else {
            TwoFingerMode.SCROLL
        }

    /**
     * Count discrete zoom notches crossed since [anchorDist].
     * Positive = spread (zoom in / Cmd+=), negative = pinch
     * (zoom out / Cmd+-). The returned [PinchNotches.newAnchorDist]
     * advances by exactly the consumed notches so the caller keeps
     * the sub-notch remainder and never double-counts. macOS only
     * accepts discrete zoom steps from non-Magic-Trackpad input, so
     * we quantise rather than stream continuous magnification.
     */
    fun pinchNotches(
        currentDist: Float,
        anchorDist: Float,
        notchPx: Float,
    ): PinchNotches {
        if (notchPx <= 0f) return PinchNotches(0, anchorDist)
        val delta = currentDist - anchorDist
        val count = (delta / notchPx).toInt()
        return PinchNotches(count, anchorDist + count * notchPx)
    }

    /** Map a notch sign to the macOS zoom chord (Cmd+= / Cmd+-). */
    fun zoomChord(notchSign: Int): KeyChord? =
        when {
            notchSign > 0 -> KeyChord(HidKeys.MOD_LGUI, HidKeys.KC_EQUAL)
            notchSign < 0 -> KeyChord(HidKeys.MOD_LGUI, HidKeys.KC_MINUS)
            else -> null
        }

    /**
     * Classify a latched 3-finger swipe once travel clears
     * [thresholdPx] on its dominant axis. Returns null until the
     * threshold is met so the caller fires exactly once per
     * gesture. Mappings follow the macOS defaults:
     *  - horizontal → Ctrl+Right / Ctrl+Left (full-screen app switch)
     *  - up → F3 (Mission Control, no modifier)
     *  - down → Ctrl+Down (App Exposé)
     */
    fun threeFingerSwipe(
        dxPx: Float,
        dyPx: Float,
        thresholdPx: Float,
    ): KeyChord? =
        when {
            abs(dxPx) > abs(dyPx) && abs(dxPx) > thresholdPx ->
                KeyChord(HidKeys.MOD_LCTRL, if (dxPx > 0f) HidKeys.KC_RIGHT else HidKeys.KC_LEFT)
            abs(dyPx) >= abs(dxPx) && abs(dyPx) > thresholdPx ->
                if (dyPx < 0f) {
                    KeyChord(0, HidKeys.KC_F3)
                } else {
                    KeyChord(HidKeys.MOD_LCTRL, HidKeys.KC_DOWN)
                }
            else -> null
        }

    /**
     * Classify a 4-finger pinch/spread once the separation change
     * clears [notchPx]. Pinch in → F4 (Launchpad); spread out →
     * F11 (Show Desktop). One-shot toggles — the caller latches so
     * the chord fires once per gesture, not per notch.
     */
    fun fourFingerZoom(
        distDeltaPx: Float,
        notchPx: Float,
    ): KeyChord? =
        when {
            distDeltaPx <= -notchPx -> KeyChord(0, HidKeys.KC_F4)
            distDeltaPx >= notchPx -> KeyChord(0, HidKeys.KC_F11)
            else -> null
        }

    /**
     * Palm-slap reject. A flat palm lands as a jump from fewer than
     * two pointers straight to four or more in under [windowMs]
     * without ever passing through a 3-pointer frame; an
     * intentional multi-finger gesture ramps 1→2→3→4. Reject only
     * that signature so legitimate fast 3- and 4-finger gestures
     * still register.
     */
    fun isPalmSlap(
        prevPointerCount: Int,
        newPointerCount: Int,
        sawThreePointers: Boolean,
        elapsedMs: Long,
        windowMs: Long = PALM_SLAP_WINDOW_MS,
    ): Boolean =
        prevPointerCount < 2 &&
            newPointerCount >= 4 &&
            !sawThreePointers &&
            elapsedMs < windowMs

    /** Pinch wins disambiguation only when it beats vertical travel by this factor. */
    const val PINCH_VS_SCROLL_RATIO = 1.5f

    /** A <2→≥4 pointer jump faster than this (ms), skipping 3, reads as a palm. */
    const val PALM_SLAP_WINDOW_MS = 80L

    // dp tunables from docs/plans/KEYBOARD_HID_RELEASE.md §4.
    // Caller multiplies by display density to get pixels.
    const val PINCH_NOTCH_DP = 36f
    const val SWIPE_THRESHOLD_DP = 80f
    const val FOUR_FINGER_NOTCH_DP = 80f
}
