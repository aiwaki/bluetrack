package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidKeys
import kotlin.math.abs

/**
 * Pure, Android-free classification of Mac-trackpad multi-touch
 * gestures into HID intents (3-/4-finger desktop keyboard chords).
 * The stateful tracking — pointer counts, gesture latches, palm-window
 * timestamps — lives in `MainActivity`'s touch listener; this object
 * owns only the decision math so it can carry JVM unit tests, mirroring
 * the `GatewayStatusReducer` / `BatteryNotifyPolicy` split.
 *
 * Two-finger pinch-to-zoom is deliberately NOT here: a generic
 * Bluetooth HID device cannot emit the macOS "magnify" gesture, and the
 * only HID stand-in (a global Cmd+= / Cmd+- chord) is page/document
 * zoom that fires context-blind — wrong semantics. Two fingers always
 * scroll; the keyboard zoom mapping was removed.
 *
 * All distances arrive in pixels: the caller converts the dp tunables
 * ([SWIPE_THRESHOLD_DP], [FOUR_FINGER_NOTCH_DP]) with the live display
 * density so the gesture feel is screen-independent.
 */
object TouchGestureClassifier {
    /**
     * A keyboard chord to fire via `MainViewModel.tapHidKey`.
     * [modifier] is an OR of `HidKeys.MOD_*` (0 = no modifier);
     * [keycode] is a HID Usage page 0x07 code.
     */
    data class KeyChord(
        val modifier: Int,
        val keycode: Int,
    )

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

    /** A <2→≥4 pointer jump faster than this (ms), skipping 3, reads as a palm. */
    const val PALM_SLAP_WINDOW_MS = 80L

    // dp tunables (caller multiplies by display density to get pixels).
    // Sized for the phone touchpad surface, not a Mac trackpad's larger
    // physical area: on-device logcat showed a 3-finger swipe only
    // yields ~45-110px of centroid travel, so the threshold must sit
    // well below the old 80dp (~220px) to fire on a reachable drag.
    const val SWIPE_THRESHOLD_DP = 28f
    const val FOUR_FINGER_NOTCH_DP = 28f
}
