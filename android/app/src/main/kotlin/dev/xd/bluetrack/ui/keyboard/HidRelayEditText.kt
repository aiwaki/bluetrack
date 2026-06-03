package dev.xd.bluetrack.ui.keyboard

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * Invisible [EditText] whose [InputConnection] is intercepted so the
 * system keyboard's edits relay to the host as HID keystrokes.
 *
 * Forwarding the IME's actual operations — `setComposingText`,
 * `commitText`, `deleteSurroundingText`, `sendKeyEvent` — instead of
 * diffing a whole text buffer keeps composing churn, autocorrect
 * replacements and pastes exact: the composing region is tracked and
 * only its minimal prefix delta is emitted.
 *
 *  - [onText] — a text delta to type (characters added).
 *  - [onBackspace] — N backspaces.
 *
 * The caller maps the text to keycodes; this class is layout-agnostic.
 */
class HidRelayEditText(
    context: Context,
) : EditText(context) {
    var onText: ((String) -> Unit)? = null
    var onBackspace: ((Int) -> Unit)? = null

    /** The IME's current composing region, mirrored locally. */
    private var composing = ""

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val base = super.onCreateInputConnection(outAttrs)
            ?: BaseInputConnection(this, true)
        return Relay(base)
    }

    /** Emit the minimal prefix delta turning [old] into [new]. */
    private fun applyDelta(old: String, new: String) {
        var common = 0
        val max = minOf(old.length, new.length)
        while (common < max && old[common] == new[common]) common++
        val deleted = old.length - common
        if (deleted > 0) onBackspace?.invoke(deleted)
        if (new.length > common) onText?.invoke(new.substring(common))
    }

    private inner class Relay(
        target: InputConnection,
    ) : InputConnectionWrapper(target, true) {
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val new = text.toString()
            applyDelta(composing, new)
            composing = new
            return super.setComposingText(text, newCursorPosition)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Commit replaces the composing region with [text].
            applyDelta(composing, text.toString())
            composing = ""
            return super.commitText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            // Composing text stays as already-typed; just stop tracking.
            composing = ""
            return super.finishComposingText()
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            // The IME re-opens composing over already-committed text
            // (e.g. backspacing into a word to re-edit it). Seed
            // [composing] with that region's current text so the next
            // setComposingText diffs against it instead of re-typing the
            // whole word (which duplicated it).
            val src = this@HidRelayEditText.text
            composing = if (src != null) {
                val a = start.coerceIn(0, src.length)
                val b = end.coerceIn(0, src.length)
                src.substring(minOf(a, b), maxOf(a, b))
            } else {
                ""
            }
            return super.setComposingRegion(start, end)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Backspaces only matter once there is no composing region —
            // composing edits already go through setComposingText.
            if (composing.isEmpty() && beforeLength > 0) {
                onBackspace?.invoke(beforeLength)
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> onBackspace?.invoke(1)
                    KeyEvent.KEYCODE_ENTER -> onText?.invoke("\n")
                    KeyEvent.KEYCODE_TAB -> onText?.invoke("\t")
                    KeyEvent.KEYCODE_SPACE -> onText?.invoke(" ")
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}
