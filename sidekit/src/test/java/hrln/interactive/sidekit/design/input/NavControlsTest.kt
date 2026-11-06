package hrln.interactive.sidekit.design.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 contract: each module's native keys map to canonical navigation in Sidekit UI. */
class NavControlsTest {

    private fun mapped(module: KeypadModule, code: Int): Int? = NavControls.applyDefault(module, code)

    private fun isDirection(code: Int) = code in NavControls.all.take(4)

    @Test fun t9AndSundialAlreadySendCanonicalKeys() {
        // T9's D-pad / OK are already what focus and select act on: nothing to rewrite.
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENDCALL)) {
            assertNull(mapped(KeypadModule.T9, code))
            assertNull(mapped(KeypadModule.Sundial, code))
        }
    }

    @Test fun gamePadAConfirms() {
        assertEquals(KeyEvent.KEYCODE_ENTER, mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_A))
        // A must become a real confirm key (Compose activates focused controls on it).
        assertEquals(NavControls.Confirm, mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun gamePadBGoesBack() {
        assertEquals(KeyEvent.KEYCODE_BACK, mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(NavControls.Back, mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test fun gamePadLeavesGameActionsAndCanonicalKeysAlone() {
        assertNull(mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_X))
        assertNull(mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_Y))
        assertNull(mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_L1))
        assertNull(mapped(KeypadModule.GamePad, KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun qwertyLetterCrossNavigates() {
        // On-device measurement: T/D/B/J around the tablet's centre are the directional cross.
        assertEquals(NavControls.Up, mapped(KeypadModule.Qwerty, KeyEvent.KEYCODE_T))
        assertEquals(NavControls.Down, mapped(KeypadModule.Qwerty, KeyEvent.KEYCODE_B))
        assertEquals(NavControls.Left, mapped(KeypadModule.Qwerty, KeyEvent.KEYCODE_D))
        assertEquals(NavControls.Right, mapped(KeypadModule.Qwerty, KeyEvent.KEYCODE_J))
    }

    @Test fun qwertyModifierKeysAndConfirmStayTyping() {
        // The modifier keys and Enter keep their jobs: only the letter cross navigates.
        for (code in listOf(QwertyLayout.SUGGEST, QwertyLayout.SYM, QwertyLayout.CASE,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_A)) {
            assertNull(mapped(KeypadModule.Qwerty, code))
        }
    }

    @Test fun everyDefaultTargetIsACanonicalNavKey() {
        NavControls.defaultFor.forEach { (module, profile) ->
            profile.forEach { (_, target) ->
                assertTrue("$module maps to non-canonical $target", target in NavControls.all)
            }
        }
    }

    @Test fun everyGamePadDefaultTargetIsADirectionConfirmOrBack() {
        NavControls.defaultFor.getValue(KeypadModule.GamePad).values.forEach {
            assertTrue("unexpected canonical target $it", isDirection(it) || it == NavControls.Confirm || it == NavControls.Back)
        }
    }
}