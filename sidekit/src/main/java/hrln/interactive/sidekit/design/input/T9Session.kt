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
 * delete acts on press and repeats while held. While a word has suggestions, D-pad left / right
 * move between them and OK takes one.
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
    private var acceptedOk = false
    private var downCode = -1

    fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // While a word has suggestions, left / right pick among them (no touch needed).
                if (engine.composing) {
                    if (down) engine.moveSelection(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    return true
                }
                if (down) engine.finish(buffer)
                return false
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_BACK,
            -> {
                // Settle the word, then let the field move the cursor / the screen go back.
                if (down) engine.finish(buffer)
                return false
            }
        }
        if (handleOk(event)) return true
        if (QwertyLayout.handles(event.keyCode)) return qwerty(event)
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

    /**
     * OK takes the highlighted suggestion; the rest of that press (repeats, release) is consumed.
     * Returns false when OK should do its usual job (no suggestions, or the suggestion is just
     * what's typed).
     */
    fun handleOk(event: KeyEvent): Boolean {
        if (!isOk(event.keyCode)) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0 && engine.composing) {
            acceptedOk = engine.acceptSelected(buffer)
            return acceptedOk
        }
        if (acceptedOk) {
            if (!down) acceptedOk = false
            return true
        }
        return false
    }

    /**
     * The mini QWERTY. Keys act on release, like T9 digits, so a long press can type the printed
     * digit or symbol instead. The up / down key steps through suggestions (there is no D-pad).
     */
    private fun qwerty(event: KeyEvent): Boolean {
        val code = event.keyCode
        val now = event.eventTime
        if (code == QwertyLayout.SUGGEST) {
            if (event.action == KeyEvent.ACTION_DOWN) engine.moveSelection(1)
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                downCode = code
                longFired = false
            } else if (downCode == code && !longFired && now - event.downTime >= keypad.longPressMs) {
                longFired = true
                when (code) {
                    QwertyLayout.CASE -> engine.key(T9Key.Pound, buffer, now, long = true)
                    else -> QwertyLayout.printed[code]?.let { engine.type(it, buffer) }
                }
            }
            KeyEvent.ACTION_UP -> {
                if (downCode == code && !longFired) {
                    when (code) {
                        QwertyLayout.CASE -> engine.key(T9Key.Pound, buffer, now)
                        QwertyLayout.SYM -> engine.tap(SYM_ID, QwertyLayout.SYMBOLS, buffer, now)
                        KeyEvent.KEYCODE_SPACE -> engine.type(if (engine.mode == T9Mode.Numeric) "0" else " ", buffer)
                        else -> if (engine.mode == T9Mode.Numeric) {
                            QwertyLayout.printed[code]?.let { engine.type(it, buffer) }
                        } else {
                            QwertyLayout.letters[code]?.let { engine.tap(code, it, buffer, now) }
                        }
                    }
                    scheduleTick()
                }
                downCode = -1
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

    private companion object {
        const val SYM_ID = 200
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun keyFor(code: Int): T9Key? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> T9Key.entries[code - KeyEvent.KEYCODE_0]
        KeyEvent.KEYCODE_STAR -> T9Key.Star
        KeyEvent.KEYCODE_POUND -> T9Key.Pound
        KeyEvent.KEYCODE_DEL -> T9Key.Delete
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> T9Key.Enter
        else -> null
    }
}
