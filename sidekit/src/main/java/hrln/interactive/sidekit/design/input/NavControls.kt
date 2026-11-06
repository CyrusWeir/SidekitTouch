package hrln.interactive.sidekit.design.input

import android.view.KeyEvent

/**
 * The navigation Sidekit's own screens move with, no matter which module is attached.
 *
 * Compose's focus system already moves on the D-pad and activates a focused control on Enter, and
 * Android treats [Back] ([KeyEvent.KEYCODE_BACK]) as "go back" everywhere. T9 and the Sundial send
 * exactly these keys natively, so they don't need help. The game controller and mini QWERTY send a
 * different set of keys (gamepad buttons, modifier keys), so [Keypad.translateForUi] rewrites
 * their native navigation keys to these canonical ones before anything else sees them. Back and
 * Options here mirror [SystemKeys].
 *
 * These are key codes, not a separate input layer: keeping them as real KeyEvents lets the rest of
 * Sidekit (T9Session, SidekitTextField, focus) keep working unchanged on the already-canonical
 * modules.
 */
object NavControls {
    const val Up = KeyEvent.KEYCODE_DPAD_UP
    const val Down = KeyEvent.KEYCODE_DPAD_DOWN
    const val Left = KeyEvent.KEYCODE_DPAD_LEFT
    const val Right = KeyEvent.KEYCODE_DPAD_RIGHT
    /** Select / open the focused control. [KeyEvent.KEYCODE_ENTER] is what T9 calls OK and ListItems act on. */
    const val Confirm = KeyEvent.KEYCODE_ENTER
    const val Back = KeyEvent.KEYCODE_BACK
    const val Options = KeyEvent.KEYCODE_ENDCALL

    /** Every canonical navigation key, for contract tests. */
    val all = listOf(Up, Down, Left, Right, Confirm, Back, Options)

    /**
     * How each module's native keys map to canonical navigation, active while the module is
     * auto-detected and no text field owns the keypad (see [Keypad.translateForUi]).
     *
     * - T9 and the Sundial already send the canonical keys, so they map nothing.
     * - The game controller's D-pad is canonical too; A confirms and B goes back (useless to
     *   anyone in Sidekit's own UI until now). X/Y and the shoulder stay game actions.
     * - Mini QWERTY: its bottom row is the modifier keys (SUGGEST / SYM / CASE = Ctrl / Alt /
     *   Shift) and its only dedicated directional control is a single up-down rocker (code 113),
     *   so navigation uses a cross of letter keys measured on-device: **T up, D left, B down,
     *   J right**. These are letters while a text field is focused and navigation keys when not
     *   (the session guard in [Keypad.translateForUi] chooses which). Enter is already Confirm.
     */
    val defaultFor: Map<KeypadModule, Map<Int, Int>> = mapOf(
        KeypadModule.T9 to emptyMap(),
        KeypadModule.Sundial to emptyMap(),
        KeypadModule.GamePad to mapOf(
            KeyEvent.KEYCODE_BUTTON_A to Confirm,
            KeyEvent.KEYCODE_BUTTON_B to Back,
        ),
        KeypadModule.Qwerty to mapOf(
            // Measured on-device: T / D / B / J around the tablet's centre form a directional cross.
            KeyEvent.KEYCODE_T to Up,
            KeyEvent.KEYCODE_D to Left,
            KeyEvent.KEYCODE_B to Down,
            KeyEvent.KEYCODE_J to Right,
        ),
    )

    /** The canonical navigation key [code] becomes in [module]'s UI, or null to keep it as sent. */
    internal fun applyDefault(module: KeypadModule, code: Int): Int? = defaultFor[module]?.get(code)
}