package hrln.interactive.sidekit.design.input

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [T9Buffer] over a Compose [TextFieldState]: each engine edit is one atomic text edit. */
internal class TextFieldT9Buffer(private val state: TextFieldState) : T9Buffer {
    override val text: CharSequence get() = state.text
    override val selectionStart: Int get() = state.selection.min
    override val selectionEnd: Int get() = state.selection.max

    override fun replace(start: Int, end: Int, text: String, selectStart: Int, selectEnd: Int) {
        state.edit {
            val len = length
            val s = start.coerceIn(0, len)
            val e = end.coerceIn(s, len)
            replace(s, e, text)
            val newLen = length
            selection = TextRange(selectStart.coerceIn(0, newLen), selectEnd.coerceIn(0, newLen))
        }
    }
}

/**
 * Turns keypad [KeyEvent]s for one focused text field into [T9Engine] calls.
 *
 * Digits act on release so a long press can type the digit itself instead (as on a Nokia);
 * delete acts on press and repeats while held.
 */
internal class T9Session(
    private val keypad: Keypad,
    val buffer: T9Buffer,
    private val scope: CoroutineScope,
) {
    private val engine get() = keypad.engine
    private var downKey: T9Key? = null
    private var longFired = false
    private var tickJob: Job? = null

    fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_BACK,
            -> {
                // Settle the word, then let the field move the cursor / the screen go back.
                if (event.action == KeyEvent.ACTION_DOWN) engine.finish(buffer)
                return false
            }
        }
        val key = keyFor(event.keyCode) ?: return false
        val now = event.eventTime
        if (key == T9Key.Delete || key == T9Key.Enter) {
            if (event.action == KeyEvent.ACTION_DOWN && (key == T9Key.Delete || event.repeatCount == 0)) {
                engine.key(key, buffer, now)
            }
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                downKey = key
                longFired = false
            } else if (downKey == key && !longFired && event.eventTime - event.downTime >= keypad.longPressMs) {
                longFired = true
                engine.key(key, buffer, now, long = true)
            }
            KeyEvent.ACTION_UP -> {
                if (downKey == key && !longFired) {
                    engine.key(key, buffer, now)
                    scheduleTick()
                }
                downKey = null
            }
        }
        return true
    }

    fun pick(index: Int) = engine.pick(index, buffer)

    fun finish() {
        tickJob?.cancel()
        engine.finish(buffer)
    }

    fun setMode(mode: T9Mode) = engine.setMode(mode, buffer)

    /** Settles a multi-tap letter when its key hasn't been pressed again in time. */
    private fun scheduleTick() {
        val deadline = engine.pendingTapDeadline
        tickJob?.cancel()
        if (deadline < 0) return
        tickJob = scope.launch {
            delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
            engine.tick(buffer, SystemClock.uptimeMillis())
        }
    }

    private fun keyFor(code: Int): T9Key? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> T9Key.entries[code - KeyEvent.KEYCODE_0]
        KeyEvent.KEYCODE_STAR -> T9Key.Star
        KeyEvent.KEYCODE_POUND -> T9Key.Pound
        KeyEvent.KEYCODE_DEL -> T9Key.Delete
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> T9Key.Enter
        else -> null
    }
}
