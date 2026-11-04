package hrln.interactive.sidekit.design.input

import android.view.KeyEvent

/**
 * Tells the attached keypad module from the keys it sends.
 *
 * Every module is the same I2C input device (`gxa535_keyboard`, vendor / product 0, no readable
 * id in sysfs), so the module is recognised by keys only it has. Measured with `getevent`:
 *
 * - T9: digits, `*`, `#` (plus D-pad, OK = `KEY_ENTER`, left soft key = Home, right = Delete).
 * - Mini QWERTY: letters, one per key (`KEY_Q` for QW...), Space, Ctrl / Alt / Shift.
 * - Sundial: centre = Play/Pause, edges = Up / Next / Down / Previous, corners = Left / Right /
 *   Tab / Enter. Turning the wheel presses the edges in sequence.
 * - Game controller: gamepad buttons.
 *
 * Keys shared between modules (D-pad, Enter, call keys, Delete) say nothing.
 */
internal object KeypadDetector {
    const val DEVICE_NAME = "gxa535_keyboard"

    fun moduleFor(event: KeyEvent): KeypadModule? {
        // Headset buttons send Play/Pause too: only trust the keypad (or injected / remapped keys,
        // which come from the virtual device).
        val name = event.device?.name
        if (name != null && name != DEVICE_NAME && event.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) return null
        val code = event.keyCode
        return when {
            code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || code == KeyEvent.KEYCODE_SPACE ||
                code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_ALT_LEFT ||
                code == KeyEvent.KEYCODE_CTRL_LEFT -> KeypadModule.Qwerty
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT ||
                code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_TAB -> KeypadModule.Sundial
            KeyEvent.isGamepadButton(code) -> KeypadModule.GamePad
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code == KeyEvent.KEYCODE_STAR || code == KeyEvent.KEYCODE_POUND ->
                KeypadModule.T9
            else -> null
        }
    }
}
