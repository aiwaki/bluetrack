package dev.xd.bluetrack.ui.gamepad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.xd.bluetrack.ui.theme.BluetrackTheme
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
) {
    val palette = BluetrackTheme.palette
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
                .size(wellSize)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = 0.06f),
                            0.7f to Color.Black.copy(alpha = 0.45f),
                        ),
                    ),
                ).border(1.dp, palette.glassBorder, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, _ ->
                            change.consume()
                            // Map pointer offset (in px relative to
                            // the gesture start, which is the press
                            // point) into a normalised vector. We
                            // reset on each gesture using `position`
                            // — the canvas does the same.
                            val px = change.position.x - size.width / 2f
                            val py = change.position.y - size.height / 2f
                            val nx = px / (size.width / 2f)
                            val ny = py / (size.height / 2f)
                            val m = hypot(nx, ny)
                            val cx = if (m > 1f) nx / m else nx
                            val cy = if (m > 1f) ny / m else ny
                            normalised = Offset(cx, cy)
                            onChange(cx, cy)
                        },
                        onDragEnd = {
                            dragging = false
                            normalised = Offset.Zero
                            onChange(0f, 0f)
                        },
                        onDragCancel = {
                            dragging = false
                            normalised = Offset.Zero
                            onChange(0f, 0f)
                        },
                    )
                },
        ) {
            // Deadzone ring.
            Box(
                modifier = Modifier
                    .size((wellSize.value * 0.24f).dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
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
                                    0f to Color.White,
                                    0.6f to Color(0xFF444444),
                                    1f to Color(0xFF1A1A1A),
                                ),
                            )
                        } else {
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.White,
                                    0.45f to palette.mintBright,
                                    1f to palette.mintDeep,
                                ),
                            )
                        },
                    ).border(2.dp, Color.White.copy(alpha = if (inDead) 0.06f else 0.18f), CircleShape),
            )
        }
        // Caption removed — earlier "L · L3" / "R · R3" labels
        // sat below the stick well at a fixed offset and routinely
        // clipped into the D-pad / face buttons or off-screen in
        // landscape. The stick's position alone telegraphs L vs R.
        @Suppress("UNUSED_EXPRESSION")
        palette
    }
}
