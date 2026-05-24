package dev.xd.bluetrack.ui.hub

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import kotlinx.coroutines.delay

/**
 * Mouse Mirror passthrough surface for the Hub.
 *
 * Flow:
 *  1. Poll `InputManager` for a connected physical pointer device
 *     (USB-OTG mouse, Bluetooth mouse paired to the phone, etc.).
 *     System devices and the touchscreen itself are filtered out.
 *  2. While no real mouse is attached → render an empty-state
 *     card with onboarding copy. The HID path stays idle.
 *  3. When a mouse appears → mount a focused `FrameLayout`,
 *     request pointer capture (API 26+), and route every captured
 *     event through the callbacks:
 *         - relative `AXIS_X / AXIS_Y` → [onMotion]
 *         - `AXIS_VSCROLL`             → [onScroll]
 *         - `ACTION_BUTTON_PRESS / RELEASE` → [onButton]
 *  4. Pointer capture hides the on-phone cursor automatically,
 *     so the real mouse's pointer effectively teleports onto the
 *     paired host's screen via the HID reports while the phone
 *     stays in the path as the filter (the BLE feedback channel
 *     can still inject host-side corrections).
 *
 * Phone cursor "disappears" via the OS-level pointer capture; we
 * do not redraw a fake cursor on the phone.
 */
@Composable
fun MouseMirrorPanel(
    modifier: Modifier,
    onMotion: (Float, Float, String) -> Unit,
    onScroll: (Float) -> Unit,
    onButton: (Int, Boolean) -> Unit,
) {
    val palette = BluetrackTheme.palette
    val context = LocalContext.current
    val inputManager = remember {
        context.getSystemService(Context.INPUT_SERVICE) as InputManager
    }
    var deviceName by remember { mutableStateOf<String?>(detectMouse(inputManager)) }

    // 1 Hz poll for hot-plug (OTG, BT pair / unpair). Cheap — the
    // ID enumeration is in-process. A proper InputDeviceListener
    // would be lower-latency but adds lifecycle bookkeeping; the
    // 1 s lag on connect is acceptable for a user-visible "waiting
    // for mouse" screen.
    LaunchedEffect(Unit) {
        while (true) {
            deviceName = detectMouse(inputManager)
            delay(1_000L)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, palette.hairline, RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        if (deviceName == null) {
            EmptyMirrorState(palette = palette)
        } else {
            MirrorCaptureSurface(
                deviceName = deviceName!!,
                palette = palette,
                onMotion = onMotion,
                onScroll = onScroll,
                onButton = onButton,
            )
        }
    }
}

@Composable
private fun BoxScope.EmptyMirrorState(palette: dev.xd.bluetrack.ui.theme.BluetrackPalette) {
    Column(
        modifier = Modifier.fillMaxSize().align(Alignment.Center),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "MIRROR MODE",
            color = palette.fg2,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Waiting for a real mouse",
            color = palette.fg0,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text =
                "Plug in a USB mouse via OTG, or pair a Bluetooth mouse to this phone. " +
                    "Once detected, its pointer disappears from the phone and every move / " +
                    "click / scroll is forwarded to the paired host.",
            color = palette.fg3,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun BoxScope.MirrorCaptureSurface(
    deviceName: String,
    palette: dev.xd.bluetrack.ui.theme.BluetrackPalette,
    onMotion: (Float, Float, String) -> Unit,
    onScroll: (Float) -> Unit,
    onButton: (Int, Boolean) -> Unit,
) {
    val container = remember { mutableStateOf<FrameLayout?>(null) }

    DisposableEffect(deviceName) {
        // Re-request capture each time the device identity flips
        // (hot-swap from USB mouse A to mouse B). Capture is
        // released on dispose so leaving the surface restores
        // normal pointer behavior.
        container.value?.post {
            container.value?.requestPointerCapture()
        }
        onDispose {
            container.value?.releasePointerCapture()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                container.value = this
                isFocusableInTouchMode = true
                isFocusable = true
                requestFocus()
                setOnCapturedPointerListener { _, ev ->
                    handleCapturedEvent(ev, onMotion, onScroll, onButton)
                    true
                }
                // Fallback: generic-motion listener catches the
                // pre-capture HOVER_MOVE stream so the first
                // mouse wiggle wakes the surface even before the
                // OS hands us captured events.
                setOnGenericMotionListener { _, ev ->
                    if (ev.isFromSource(InputDevice.SOURCE_MOUSE) && ev.action == MotionEvent.ACTION_HOVER_MOVE) {
                        val dx = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                        val dy = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                        if (dx != 0f || dy != 0f) onMotion(dx, dy, "Mirror mouse")
                        true
                    } else {
                        false
                    }
                }
                post { requestPointerCapture() }
            }
        },
    )

    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "MIRROR ACTIVE",
            color = palette.crit,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = deviceName,
            color = palette.fg0,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Text(
        text = "Pointer captured — move the real mouse",
        color = palette.fg3,
        fontSize = 10.sp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 6.dp),
    )
}

private fun handleCapturedEvent(
    ev: MotionEvent,
    onMotion: (Float, Float, String) -> Unit,
    onScroll: (Float) -> Unit,
    onButton: (Int, Boolean) -> Unit,
) {
    when (ev.actionMasked) {
        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
            // Under pointer capture, AXIS_X/Y carry RELATIVE deltas.
            val dx = ev.getAxisValue(MotionEvent.AXIS_X)
            val dy = ev.getAxisValue(MotionEvent.AXIS_Y)
            if (dx != 0f || dy != 0f) onMotion(dx, dy, "Mirror mouse")
            val vscroll = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vscroll != 0f) onScroll(vscroll)
        }
        MotionEvent.ACTION_SCROLL -> {
            val vscroll = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vscroll != 0f) onScroll(vscroll)
        }
        MotionEvent.ACTION_BUTTON_PRESS -> {
            onButton(ev.actionButton, true)
        }
        MotionEvent.ACTION_BUTTON_RELEASE -> {
            onButton(ev.actionButton, false)
        }
    }
}

/**
 * Return the friendly name of the first attached non-virtual
 * pointer device, or `null` when only the touchscreen + system
 * virtuals are present. We accept both `SOURCE_MOUSE` (real
 * mouse) and `SOURCE_MOUSE_RELATIVE` (some Bluetooth mice
 * advertise this) and exclude `SOURCE_TOUCHSCREEN` so the phone's
 * own digitizer never trips detection.
 */
private fun detectMouse(inputManager: InputManager): String? {
    for (id in inputManager.inputDeviceIds) {
        val dev = inputManager.getInputDevice(id) ?: continue
        if (dev.isVirtual) continue
        val sources = dev.sources
        val isMouse =
            sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
                sources and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE
        val isTouchscreen =
            sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN
        if (isMouse && !isTouchscreen) return dev.name
    }
    return null
}
