package hrln.interactive.sidekit.design.input

import android.view.KeyEvent

/**
 * The Sidephone keypad's two top keys, as measured on the device (`getevent` on
 * `gxa535_keyboard`, mapped by the stock `Generic.kl`):
 *
 * - Top left: `KEY_PHONE` (scancode 169) -> [KeyEvent.KEYCODE_CALL]. SideKit makes it **Back**.
 *   The system launches the dialer on CALL before apps see it, so this needs the keypad
 *   accessibility service; a long press still opens the dialer.
 * - Top right: `KEY_PICKUP_PHONE` (scancode 445) -> [KeyEvent.KEYCODE_ENDCALL]. SideKit makes it
 *   **Options**: it reaches the app normally. [KeyEvent.KEYCODE_MENU] is treated the same so other
 *   keypads with a menu key work too.
 */
object SystemKeys {
    fun isBack(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_CALL

    fun isOptions(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_ENDCALL || event.keyCode == KeyEvent.KEYCODE_MENU

    /**
     * Hold the Back key this long to open the dialer instead. Kept separate from
     * [KeypadTiming.LONG_PRESS_MS]: opening the dialer is a deliberate, destructive-feeling
     * gesture, so it stays longer than an ordinary keypad hold.
     */
    const val LONG_PRESS_MS = 600L
}
