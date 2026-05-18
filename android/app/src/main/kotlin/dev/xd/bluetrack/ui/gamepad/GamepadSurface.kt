package dev.xd.bluetrack.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import dev.xd.bluetrack.ui.theme.BluetrackTokens
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
    var diagOpen by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            TopRail(
                hostName = hostName,
                onExit = onExit,
                diagOpen = diagOpen,
                onToggleDiag = { diagOpen = !diagOpen },
            )
            // Main row: 4 equal columns.
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
                        label = "LB",
                        digital = false,
                        onChange = { p -> onButton("LB", p) },
                    )
                    Trigger(
                        label = "LT",
                        digital = true,
                        onChange = { p -> onButton("LT", p) },
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
                        label = "RB",
                        digital = false,
                        onChange = { p -> onButton("RB", p) },
                    )
                    Trigger(
                        label = "RT",
                        digital = true,
                        onChange = { p -> onButton("RT", p) },
                    )
                    Stick(
                        label = "R",
                        onChange = { x, y -> onStickMotion("R", x, y) },
                        modifier = Modifier.size(110.dp),
                    )
                }
            }
        }
        if (diagOpen) {
            DiagFold(seq = seq, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun TopRail(
    hostName: String,
    onExit: () -> Unit,
    diagOpen: Boolean,
    onToggleDiag: () -> Unit,
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
        // Diag handle.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                .btGlass(strong = false, shape = RoundedCornerShape(BluetrackTokens.RadiusSm))
                .border(
                    1.dp,
                    if (diagOpen) palette.mintBright else palette.glassBorder,
                    RoundedCornerShape(BluetrackTokens.RadiusSm),
                ).clickable(onClick = onToggleDiag),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "≡",
                color = if (diagOpen) palette.mintBright else palette.fg1,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DiagFold(
    seq: Long,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Column(
        modifier = modifier
            .padding(end = 28.dp, top = 56.dp, bottom = 14.dp)
            .clip(shape)
            .btGlass(strong = true, shape = shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "LIVE STATE",
            color = palette.fg2,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
        DiagLine(k = "Seq", v = "#$seq")
        DiagLine(k = "Trigger pressure", v = "digital only")
        // Last 6 reports — synthetic placeholder until the
        // transport exposes a real per-report log; mirrors the
        // canvas mock.
        Text(
            text = "LAST 6 REPORTS",
            color = palette.fg2,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        (0 until 6).forEach { i ->
            Text(
                text = "#${seq - i} · ${(i * 8 + 12).toString().padStart(3, '0')}ms · 7B",
                color = palette.fg2,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DiagLine(k: String, v: String) {
    val palette = BluetrackTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = k, color = palette.fg2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(text = v, color = palette.fg0, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
