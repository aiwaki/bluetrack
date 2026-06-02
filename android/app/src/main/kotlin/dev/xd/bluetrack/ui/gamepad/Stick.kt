package dev.xd.bluetrack.ui.gamepad

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Analog stick. Mirrors canvas `Stick` from
 * `docs/design/v1/gamepad.jsx`.
 *
 *  - 88 dp circular surface, radial-gradient depth (dark centre,
 *    glassy highlight top-left).
 *  - Dashed deadzone ring at 24 % radius.
 *  - 36 dp thumb that follows the drag and snaps back to centre on
 *    release. Inside the deadzone the thumb renders with a neutral
 *    grey gradient; outside it lights up with the canvas mint
 *    (mintBright → mintDeep) + a 16 dp glow.
 *  - Label rendered beneath: "L · L3" or "R · R3" (mono caps).
 *
 * Drag emits normalised `[-1, 1]` coordinates via [onChange]; the
 * caller decides whether to forward those to the HID engine. The
 * stick keeps local state and clamps magnitude to 1 so the thumb
 * never leaves the well.
 */
@Composable
fun Stick(
    label: String,
    onChange: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Stick press (L3 / R3). Called with `pressed = true` when the
     * user holds the well without dragging past touch slop, and
     * `pressed = false` on release. A quick tap fires both in
     * rapid succession (momentary click); a long hold keeps
     * `pressed = true` until the finger lifts. Drag gestures
     * never emit a press — the gesture handler waits a short
     * grace before committing so a drag-start does not register
     * a phantom L3 click.
     */
    onPress: (Boolean) -> Unit = {},
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val palette = BluetrackTheme.palette
    // Click feedback: scale burst on tap. snapTo(0.92) then spring
    // back to 1.0 — reads as a real stick depressing and rebounding.
    val clickBurst = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // Sustained-press visual state. Set to true while the gesture
    // handler holds `onPress(true)` (long-hold = L3 / R3 latched).
    // Drives the well border, glow ring, and the corner badge so
    // the user can SEE that the stick click is being held — not
    // just feel the haptic at the start.
    var pressedHeld by remember { mutableStateOf(false) }
    val pressBorderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (pressedHeld) palette.crit else palette.glassBorder,
        animationSpec = androidx.compose.animation.core
            .tween(140),
        label = "stick-press-border",
    )
    val pressBorderWidth by animateFloatAsState(
        targetValue = if (pressedHeld) 2.5f else 1f,
        animationSpec = androidx.compose.animation.core
            .tween(140),
        label = "stick-press-border-width",
    )
    val wellSize = 88.dp
    val thumbSize = 36.dp
    val travel = 22f // px travel from centre when normalised = ±1

    var dragging by remember { mutableStateOf(false) }
    var normalised by remember { mutableStateOf(Offset.Zero) }
    val target = if (dragging) normalised else Offset.Zero
    val animX by animateFloatAsState(
        targetValue = target.x,
        animationSpec = if (dragging) tween(0) else tween(220),
        label = "stick-x",
    )
    val animY by animateFloatAsState(
        targetValue = target.y,
        animationSpec = if (dragging) tween(0) else tween(220),
        label = "stick-y",
    )
    val inDead = hypot(animX, animY) < 0.12f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // requiredSize (not size) so the well is ALWAYS an exact
                // wellSize×wellSize square → a true circle. With plain
                // size(), a height-constrained parent (the last child in
                // an overflowing thumb column) coerced the height below
                // wellSize and the clipped circle rendered as a squished
                // ellipse. requiredSize ignores the incoming constraint.
                .requiredSize(wellSize)
                .scale(clickBurst.value)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to palette.bg2,
                            0.7f to palette.bg3,
                        ),
                    ),
                ).border(pressBorderWidth.dp, pressBorderColor, CircleShape)
                // Combined press / drag detector. One
                // `awaitEachGesture` per touch:
                //
                //  1. ACTION_DOWN starts a 150ms holdJob. If the
                //     finger stays inside the touch-slop circle
                //     past that, the stick emits `onPress(true)`
                //     with haptic + scale burst — sustained press
                //     mode, host sees L3 / R3 held.
                //  2. If touch slop is crossed BEFORE the hold
                //     fires (or after), the gesture commits to
                //     drag. holdJob is cancelled; if it already
                //     latched press we release immediately so the
                //     host does not see a phantom L3 click at the
                //     start of a stick motion.
                //  3. On finger lift:
                //     - drag mode → onDragEnd, motion zeros out.
                //     - press mode → onPress(false).
                //     - quick tap (under 150ms, no drag, no hold
                //       latched) → fire press(true)+press(false)
                //       back-to-back so a brief tap still reads
                //       as a momentary click in-game.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var holdLatched = false
                        var dragLatched = false
                        val holdJob = scope.launch {
                            kotlinx.coroutines.delay(150L)
                            if (!dragLatched) {
                                holdLatched = true
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                clickBurst.snapTo(0.92f)
                                clickBurst.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                                pressedHeld = true
                                onPress(true)
                            }
                        }

                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }

                        if (slopChange != null) {
                            // Drag mode. Cancel the pending hold;
                            // if it already fired, release press
                            // before motion starts so the host
                            // sees the L3 release cleanly.
                            dragLatched = true
                            holdJob.cancel()
                            if (holdLatched) {
                                pressedHeld = false
                                onPress(false)
                                holdLatched = false
                            }
                            dragging = true
                            // Process the slop-crossing change as
                            // the first drag sample, then loop on
                            // subsequent changes via `drag`.
                            val px0 = slopChange.position.x - size.width / 2f
                            val py0 = slopChange.position.y - size.height / 2f
                            val nx0 = px0 / (size.width / 2f)
                            val ny0 = py0 / (size.height / 2f)
                            val m0 = hypot(nx0, ny0)
                            val cx0 = if (m0 > 1f) nx0 / m0 else nx0
                            val cy0 = if (m0 > 1f) ny0 / m0 else ny0
                            normalised = Offset(cx0, cy0)
                            onChange(cx0, cy0)

                            drag(down.id) { change ->
                                val px = change.position.x - size.width / 2f
                                val py = change.position.y - size.height / 2f
                                val nx = px / (size.width / 2f)
                                val ny = py / (size.height / 2f)
                                val m = hypot(nx, ny)
                                val cx = if (m > 1f) nx / m else nx
                                val cy = if (m > 1f) ny / m else ny
                                normalised = Offset(cx, cy)
                                onChange(cx, cy)
                                change.consume()
                            }

                            dragging = false
                            normalised = Offset.Zero
                            onChange(0f, 0f)
                        } else {
                            // Finger lifted before touch slop. We
                            // are in tap or hold territory. The
                            // `awaitTouchSlopOrCancellation`
                            // restricted scope forbids calling
                            // `holdJob.join()` here, but
                            // `delay(150L)` is cooperatively
                            // cancellable so `holdJob.cancel()`
                            // alone is enough — either it ran
                            // (and `holdLatched` is true) or
                            // cancellation aborted the delay
                            // before `onPress(true)` could fire.
                            holdJob.cancel()
                            if (holdLatched) {
                                pressedHeld = false
                                onPress(false)
                            } else {
                                // Quick tap — fire press+release
                                // pair with haptic + visual burst
                                // so the user gets the same click
                                // feel they had before this
                                // refactor.
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                scope.launch {
                                    clickBurst.snapTo(0.92f)
                                    clickBurst.animateTo(
                                        targetValue = 1f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                                onPress(true)
                                onPress(false)
                            }
                        }
                    }
                },
        ) {
            // Deadzone ring.
            Box(
                modifier = Modifier
                    .size((wellSize.value * 0.24f).dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .border(1.dp, palette.hairline, CircleShape),
            )
            // Thumb. `offset` is in dp, so map normalised → travel
            // (dp). With `wellSize = 88 dp` and `travel = 22`,
            // normalised 1.0 → 22 dp off centre.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (animX * travel).dp, y = (animY * travel).dp)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(
                        if (inDead) {
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to palette.fg0,
                                    0.6f to palette.fg2,
                                    1f to palette.bg3,
                                ),
                            )
                        } else {
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to palette.fg0,
                                    0.45f to palette.crit,
                                    1f to palette.crit.copy(alpha = 0.6f),
                                ),
                            )
                        },
                    ).border(2.dp, palette.hairline.copy(alpha = if (inDead) 0.5f else 0.9f), CircleShape),
            )
        }
        // Caption removed — earlier "L · L3" / "R · R3" labels
        // sat below the stick well at a fixed offset and routinely
        // clipped into the D-pad / face buttons or off-screen in
        // landscape. The stick's position alone telegraphs L vs R.
        //
        // Hold badge: tiny "L3" / "R3" pill that fades in while
        // the user is sustaining the press. Sits at the top-right
        // of the stick's bounding box so it doesn't fight the
        // thumb visual. Crossfades over 140ms — fast enough to
        // feel reactive, slow enough not to flicker on quick taps
        // that pulse `pressedHeld` for a single frame.
        androidx.compose.animation.AnimatedVisibility(
            visible = pressedHeld,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core
                    .tween(140),
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core
                    .tween(140),
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(999.dp),
                    ).background(palette.crit)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                androidx.compose.material3.Text(
                    text = "${label}3",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 9.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                )
            }
        }
    }
}
