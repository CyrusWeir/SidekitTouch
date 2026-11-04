package hrln.interactive.sidekit.design.input

import android.view.KeyEvent

/**
 * The mini QWERTY module, as measured on the device (`getevent` on `gxa535_keyboard`). Each
 * two-letter key reports only its first letter (QW -> `KEY_Q`), so letters are typed by multi-tap
 * between the key's pair; long-pressing a key types the digit or symbol printed above it.
 *
 * The bottom row reports modifiers: the up / down arrows `KEY_LEFTCTRL`, SYM `KEY_LEFTALT`, aA
 * `KEY_LEFTSHIFT`. Delete, Enter and Space are what they say. The top-row centre dot (Home) and
 * right key (Recents) are taken by the system.
 */
internal object QwertyLayout {
    /** Letters each key steps through. */
    val letters: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_Q to "qw", KeyEvent.KEYCODE_E to "er", KeyEvent.KEYCODE_T to "ty",
        KeyEvent.KEYCODE_U to "ui", KeyEvent.KEYCODE_O to "op",
        KeyEvent.KEYCODE_A to "as", KeyEvent.KEYCODE_D to "df", KeyEvent.KEYCODE_G to "gh",
        KeyEvent.KEYCODE_J to "jk", KeyEvent.KEYCODE_L to "l",
        KeyEvent.KEYCODE_Z to "zx", KeyEvent.KEYCODE_C to "cv", KeyEvent.KEYCODE_B to "bn",
        KeyEvent.KEYCODE_M to "m",
    )

    /** What a long press types: the legend printed above each key. */
    val printed: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_Q to "!", KeyEvent.KEYCODE_E to "1", KeyEvent.KEYCODE_T to "2",
        KeyEvent.KEYCODE_U to "3", KeyEvent.KEYCODE_O to ".",
        KeyEvent.KEYCODE_A to "?", KeyEvent.KEYCODE_D to "4", KeyEvent.KEYCODE_G to "5",
        KeyEvent.KEYCODE_J to "6", KeyEvent.KEYCODE_L to ",",
        KeyEvent.KEYCODE_Z to "@", KeyEvent.KEYCODE_C to "7", KeyEvent.KEYCODE_B to "8",
        KeyEvent.KEYCODE_M to "9", KeyEvent.KEYCODE_SPACE to "0",
    )

    /** SYM steps through these (the key has no letters, and the layout no other punctuation key). */
    const val SYMBOLS = "*+'\"-=/:;()&#%\$"

    const val SUGGEST = KeyEvent.KEYCODE_CTRL_LEFT
    const val SYM = KeyEvent.KEYCODE_ALT_LEFT
    const val CASE = KeyEvent.KEYCODE_SHIFT_LEFT

    /** Keys the session types or acts on (not counting Delete / Enter, shared with T9). */
    fun handles(code: Int) = code in letters || code == KeyEvent.KEYCODE_SPACE || code == SUGGEST ||
        code == SYM || code == CASE
}
