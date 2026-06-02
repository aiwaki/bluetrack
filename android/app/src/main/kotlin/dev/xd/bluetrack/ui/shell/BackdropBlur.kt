package dev.xd.bluetrack.ui.shell

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp

/**
 * Hand-rolled backdrop blur for Bluetrack glass surfaces.
 *
 * Compose has no first-class `backdrop-filter: blur()`. The standard
 * trick (which the Haze library also uses) is:
 *
 *  1. Record the whole backdrop subtree — the aurora + scrolling
 *     content — into a single shared [GraphicsLayer]
 *     ([Modifier.captureBackdrop]).
 *  2. A glass overlay drawn on top of that subtree re-draws the SAME
 *     layer, translated so the slice sitting behind the overlay lines
 *     up, with a [BlurEffect] applied ([Modifier.backdropBlur]). The
 *     overlay then paints its translucent tint on top, so the blurred
 *     content shows through — the Liquid Glass look.
 *
 * `BlurEffect` (RenderEffect) needs API 31+. On older devices the
 * blur is skipped and the caller's opaque tint carries the surface,
 * so the layout never changes — only the polish degrades gracefully.
 *
 * Coordinate model: the backdrop is recorded at its own
 * `positionInRoot`; each overlay knows its own `positionInRoot`, so
 * translating the layer by `(backdropOrigin - overlayOrigin)` aligns
 * the recorded pixels under the overlay regardless of where it sits.
 */
class BackdropState {
    /** Where the recorded backdrop layer's origin sits in root space. */
    var origin: Offset by mutableStateOf(Offset.Zero)

    /** The shared layer holding the recorded (un-blurred) backdrop. */
    var layer: GraphicsLayer? by mutableStateOf(null)
}

val backdropBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Record everything this modifier wraps into [layer] and draw it
 * normally. Glass overlays elsewhere in the same root sample that
 * layer (via [state]) for their blur. The layer's
 * [GraphicsLayer.renderEffect] is reset to `null` here every frame so
 * the visible backdrop is never accidentally blurred by an overlay's
 * leftover effect.
 *
 * The layer reference is published to [state] in composition (see the
 * caller's `SideEffect`), NOT here — writing snapshot state during the
 * draw phase is not reliably committed for same-frame readers.
 */
fun Modifier.captureBackdrop(
    state: BackdropState,
    layer: GraphicsLayer,
): Modifier =
    this
        .onGloballyPositioned { state.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            layer.renderEffect = null
            drawLayer(layer)
        }

/**
 * Draw the recorded backdrop, blurred and clipped to [shape], behind
 * this element. Apply BEFORE the translucent tint / border so the
 * blurred content sits under them. No-op (returns `this`) until the
 * backdrop layer exists and the platform supports [BlurEffect].
 */
fun Modifier.backdropBlur(
    state: BackdropState,
    shape: Shape,
    blurRadius: Dp,
): Modifier = composed {
    if (!backdropBlurSupported) return@composed this
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val layer = state.layer ?: return@drawBehind
            val r = blurRadius.toPx()
            layer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
            val dx = state.origin.x - origin.x
            val dy = state.origin.y - origin.y
            translate(dx, dy) { drawLayer(layer) }
        }
}
