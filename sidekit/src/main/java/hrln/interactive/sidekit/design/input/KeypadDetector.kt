package hrln.interactive.sidekit.design.input

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Tells the attached keypad module from the keys it sends.
 *
 * Every module appears as an input device named `gxa535_*`. This phone reports `gxa535_media`,
 * `gxa535_qwerty`, `gxa535_t9`, `gxa535_gamepad` and `gxa535_phone`; older firmware exposed
 * everything as `gxa535_keyboard`. The module is recognised by keys only it has. Measured with
 * `getevent`:
 *
 * - T9: digits, `*`, `#` (plus D-pad, OK = `KEY_ENTER`, left soft key = Home, right = Delete).
 * - Mini QWERTY: letters, one per key (`KEY_Q` for QW...), Space, Ctrl / Alt / Shift.
 * - Sundial: centre = Play/Pause, ring = Up / Next / Down / Previous, corners = Left / Right /
 *   Tab / Enter. The ring reports eight separate keys; it has no rotation axis.
 * - Game controller: gamepad buttons.
 *
 * Keys shared between modules (D-pad, Enter, call keys, Delete) say nothing.
 */
internal object KeypadDetector {
    const val DEVICE_PREFIX = "gxa535_"

    /** True for a keypad module, the virtual keyboard, or an event with no readable device. */
    internal fun isKeypadDevice(name: String?, deviceId: Int): Boolean = name == null || name.startsWith(DEVICE_PREFIX) || deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD

    fun moduleFor(event: KeyEvent): KeypadModule? {
        // Headset buttons send Play/Pause too: only trust the keypad (or injected / remapped keys,
        // which come from the virtual device).
        if (!isKeypadDevice(event.device?.name, event.deviceId)) return null
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
