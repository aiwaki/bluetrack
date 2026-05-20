package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import kotlinx.coroutines.delay

/**
 * Full landscape gamepad surface. Mirrors canvas `GamepadScreen`
 * + `GamepadLandscape` (`docs/design/v1/gamepad.jsx`).
 *
 * Layout (left → right):
 *
 *   ┌────────────────────────────────────────────────────┐
 *   │ EXIT  HOST · 14 ms  WAKE TRAIN  diag-handle        │  ← top rail
 *   ├──────┬──────────────┬──────────────┬───────────────┤
 *   │  LB  │              │              │      RB       │
 *   │  LT  │  D-Pad       │  Face btns   │      RT       │
 *   │  L●  │  centre rail │  FrameCount  │      R●       │
 *   └──────┴──────────────┴──────────────┴───────────────┘
 *
 * Pure presentation + local press / drag state. Stick drags fire
 * [onStickMotion] (left = source `"L"`, right = `"R"`), button /
 * trigger / D-pad / face presses fire [onButton]; the host
 * activity wires those into `MainViewModel` / the HID transport.
 *
 * The connection lozenge and frame counter pull from
 * [hostName] / [seq] / [pulse] passed in by the caller so the
 * surface stays a stateless composable easy to preview.
 */
@Composable
fun GamepadSurface(
    hostName: String,
    seq: Long,
    pulse: Boolean,
    onExit: () -> Unit,
    onStickMotion: (source: String, x: Float, y: Float) -> Unit,
    onButton: (label: String, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Respect status / navigation bar insets — earlier the
                // top rail (Exit pill, host chip) sat under the
                // carrier-name / clock band and got clipped on notch
                // devices.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            TopRail(
                hostName = hostName,
                onExit = onExit,
            )
            // Main row: 4 equal columns. Reorganised to match the
            // physical Xbox controller layout — LT/RT now sit at
            // the top of each side column (analog triggers sit on
            // top of the bumpers on real hardware when the
            // controller is held), LB/RB below, then the
            // thumbstick anchored at the bottom corner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Trigger(
                        label = "LT",
                        digital = true,
                        onChange = { p -> onButton("LT", p) },
                    )
                    Trigger(
                        label = "LB",
                        digital = false,
                        onChange = { p -> onButton("LB", p) },
                    )
                    Stick(
                        label = "L",
                        onChange = { x, y -> onStickMotion("L", x, y) },
                        modifier = Modifier.size(110.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DPad(onHat = { hat -> onButton("HAT_$hat", hat != 8) })
                    CenterRail(onPress = onButton)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FaceButtons(onChange = onButton)
                    FrameCounter(seq = seq, pulse = pulse)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Trigger(
                        label = "RT",
                        digital = true,
                        onChange = { p -> onButton("RT", p) },
                    )
                    Trigger(
                        label = "RB",
                        digital = false,
                        onChange = { p -> onButton("RB", p) },
                    )
                    Stick(
                        label = "R",
                        onChange = { x, y -> onStickMotion("R", x, y) },
                        modifier = Modifier.size(110.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopRail(
    hostName: String,
    onExit: () -> Unit,
) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Exit pill.
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(999.dp))
                .btGlass(strong = false, shape = RoundedCornerShape(999.dp))
                .border(1.dp, palette.glassBorder, RoundedCornerShape(999.dp))
                .clickable(onClick = onExit)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "‹",
                color = palette.fg1,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "EXIT",
                color = palette.fg1,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.6.sp,
            )
        }
        ConnectionLozenge(host = hostName, latency = "—")
        WakeTrainChip()
        Box(modifier = Modifier.weight(1f))
    }
}

/**
 * Convenience holder for the 130 ms frame-counter pulse used by
 * the canvas. Drives [seq] forward and toggles [pulse] for ~80 ms
 * each step. Kept here so callers do not have to re-derive the
 * cadence on every screen.
 */
@Composable
fun rememberFrameCounterState(): FrameCounterState {
    val seq = remember { mutableLongStateOf(48_217L) }
    val pulse = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(130L)
            seq.longValue += 1
            pulse.value = true
            delay(80L)
            pulse.value = false
        }
    }
    return FrameCounterState(seq.longValue, pulse.value)
}

data class FrameCounterState(
    val seq: Long,
    val pulse: Boolean,
)
