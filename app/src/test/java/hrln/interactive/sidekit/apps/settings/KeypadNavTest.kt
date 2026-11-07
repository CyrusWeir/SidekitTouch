package hrln.interactive.sidekit.apps.settings

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.T9Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 1: [Keypad.translateForUi] rewrites module-native keys to canonical navigation; [Keypad.translate] stays raw. */
@RunWith(AndroidJUnit4::class)
class KeypadNavTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun keypad(module: KeypadModule) = Keypad.forTesting(scope, T9Words.Empty, module = module)

    private fun down(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)

    @Test fun gamePadAConfirmsAndBGoesBackInUiButStaysRawOtherwise() {
        val keypad = keypad(KeypadModule.GamePad)
        assertEquals(KeyEvent.KEYCODE_ENTER, keypad.translateForUi(down(KeyEvent.KEYCODE_BUTTON_A))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_BACK, keypad.translateForUi(down(KeyEvent.KEYCODE_BUTTON_B))?.keyCode)
        // The custom-only path keeps the gamepad's own buttons, for typing / projection.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, keypad.translate(down(KeyEvent.KEYCODE_BUTTON_A))?.keyCode)
    }

    @Test fun gamePadXDpadAndCanonicalKeysPassThrough() {
        val keypad = keypad(KeypadModule.GamePad)
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, keypad.translateForUi(down(KeyEvent.KEYCODE_BUTTON_X))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, keypad.translateForUi(down(KeyEvent.KEYCODE_DPAD_UP))?.keyCode)
    }

    @Test fun customMappingSkipsDefaults() {
        val keypad = keypad(KeypadModule.GamePad)
        keypad.useCustomMapping(KeypadModule.GamePad)
        // Under custom mapping the user owns every key: A stays A unless they bind it.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, keypad.translateForUi(down(KeyEvent.KEYCODE_BUTTON_A))?.keyCode)
    }

    @Test fun t9AndSundialPassThroughUntouched() {
        for (module in listOf(KeypadModule.T9, KeypadModule.Sundial)) {
            val k = keypad(module)
            assertEquals(KeyEvent.KEYCODE_DPAD_UP, k.translateForUi(down(KeyEvent.KEYCODE_DPAD_UP))?.keyCode)
            assertEquals(KeyEvent.KEYCODE_ENTER, k.translateForUi(down(KeyEvent.KEYCODE_ENTER))?.keyCode)
        }
    }

    @Test fun qwertyModifierKeysAreNotRewrittenInUi() {
        val k = keypad(KeypadModule.Qwerty)
        // SUGGEST / SYM / CASE are Ctrl / Alt / Shift: they must stay typing keys in UI too.
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, k.translateForUi(down(KeyEvent.KEYCODE_CTRL_LEFT))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, k.translateForUi(down(KeyEvent.KEYCODE_ALT_LEFT))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, k.translateForUi(down(KeyEvent.KEYCODE_SHIFT_LEFT))?.keyCode)
    }

    @Test fun qwertyLetterCrossNavigatesWhenNotTyping() {
        val k = keypad(KeypadModule.Qwerty)
        // Measured on-device: T / D / B / J are the directional cross (type only while a field is focused).
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, k.translateForUi(down(KeyEvent.KEYCODE_T))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, k.translateForUi(down(KeyEvent.KEYCODE_B))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, k.translateForUi(down(KeyEvent.KEYCODE_D))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, k.translateForUi(down(KeyEvent.KEYCODE_J))?.keyCode)
        // A cross member typed by the raw path (custom mapping off) stays its printed letter pair.
        assertEquals(KeyEvent.KEYCODE_T, k.translate(down(KeyEvent.KEYCODE_T))?.keyCode)
    }

    @Test fun qwertyNonCrossKeysPassThrough() {
        val k = keypad(KeypadModule.Qwerty)
        assertEquals(KeyEvent.KEYCODE_A, k.translateForUi(down(KeyEvent.KEYCODE_A))?.keyCode)
        assertEquals(KeyEvent.KEYCODE_ENTER, k.translateForUi(down(KeyEvent.KEYCODE_ENTER))?.keyCode)
    }
}