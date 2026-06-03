package dev.xd.bluetrack.ui.keyboard

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xd.bluetrack.engine.HidKeys
import dev.xd.bluetrack.ui.shell.btGlass
import dev.xd.bluetrack.ui.theme.BluetrackTheme
import dev.xd.bluetrack.ui.theme.BluetrackTokens

/**
 * System-keyboard relay. Focuses an invisible [HidRelayEditText] so the
 * user's own OS keyboard (Gboard, etc.) opens — swipe, autocorrect,
 * voice, symbols and languages for free — and forwards what it produces
 * to the host as HID keyboard reports via [onType].
 *
 * The EditText's InputConnection reports exact IME operations; here we
 * only map the resulting text deltas to US-layout keycodes ([charToHid])
 * and feed them through the paced key queue in the ViewModel.
 *
 * Stays open while the touchpad above is used: if focus is lost while
 * the user still wants the keyboard, it is re-requested so a touchpad
 * tap never dismisses the IME.
 *
 * Limitation: a boot HID keyboard sends US-layout keycodes, so only
 * ASCII maps. Non-ASCII (Cyrillic, emoji, accents) is dropped — that
 * needs a host-specific Unicode path, tracked separately.
 */
@Composable
fun KeyboardRelay(
    onType: (modifier: Int, keycode: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BluetrackTheme.palette
    val context = LocalContext.current
    val imm = remember {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    var editText by remember { mutableStateOf<HidRelayEditText?>(null) }
    // True while the user wants the keyboard up; drives focus re-grab so
    // tapping the touchpad doesn't dismiss the IME.
    val want = remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(BluetrackTokens.RadiusMd)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .btGlass(strong = false, shape = shape)
                .clickable {
                    val field = editText
                    if (active) {
                        want.value = false
                        field?.clearFocus()
                        field?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
                    } else if (field != null) {
                        want.value = true
                        field.requestFocus()
                        field.post { imm.showSoftInput(field, 0) }
                    }
                }.padding(BluetrackTokens.Sp4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BluetrackTokens.Sp3),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(BluetrackTokens.RadiusSm))
                    .background(palette.cool.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "⌨", color = palette.cool, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (active) "Keyboard active" else "Keyboard",
                    color = palette.fg0,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (active) {
                        "Type on your keyboard — keys go to the host (use a US layout)"
                    } else {
                        "Tap to type to the host with your system keyboard"
                    },
                    color = palette.fg2,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = if (active) "HIDE" else "OPEN ↑",
                color = if (active) palette.crit else palette.mintBright,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
            )
        }

        // Invisible capture field. 1 dp + alpha 0 keeps it off-screen
        // without losing focusability / the IME.
        AndroidView(
            factory = { ctx ->
                HidRelayEditText(ctx).apply {
                    isFocusableInTouchMode = true
                    onText = { text ->
                        text.forEach { c ->
                            KeyRelayCodec.charToHid(c)?.let { onType(it[0], it[1]) }
                        }
                    }
                    onBackspace = { n ->
                        repeat(n) { onType(0, HidKeys.KC_BACKSPACE) }
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        active = hasFocus
                        if (!hasFocus && want.value) {
                            // A touchpad tap stole focus — take it back so
                            // the keyboard stays up. requestFocus ONLY (no
                            // showSoftInput): focus returns in the same
                            // frame so the IME never hides, and not
                            // re-showing keeps its current panel (e.g. the
                            // clipboard) instead of snapping back to keys.
                            post { requestFocus() }
                        }
                    }
                    editText = this
                }
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f),
        )
    }
}
