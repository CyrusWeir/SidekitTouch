package hrln.interactive.sidekit.ui

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The keypad's top keys: top-right (ENDCALL) is Options, top-left (CALL) is Back. */
@RunWith(AndroidJUnit4::class)
class KeypadKeysTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun press(code: Int): Boolean {
        var handled = false
        compose.runOnIdle {
            handled = compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) and
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
        return handled
    }

    @Test fun optionsKeyShowsTheScreensOptionsAndBackClosesThem() {
        assertTrue(press(KeyEvent.KEYCODE_ENDCALL))
        compose.onNodeWithContentDescription("Switch grid or list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close options").assertIsDisplayed()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Close options").assertDoesNotExist()
        compose.onNodeWithText("List").assertDoesNotExist()
    }

    @Test fun choosingAnOptionRunsItAndClosesTheBar() {
        press(KeyEvent.KEYCODE_ENDCALL)
        compose.onNodeWithContentDescription("Switch grid or list").performClick()
        compose.onNodeWithContentDescription("Close options").assertDoesNotExist()
        // The launcher switched to its list layout.
        compose.onNodeWithText("Sidemessage").assertIsDisplayed()
    }

    @Test fun optionsKeyAgainClosesTheBar() {
        press(KeyEvent.KEYCODE_ENDCALL)
        press(KeyEvent.KEYCODE_ENDCALL)
        compose.onNodeWithContentDescription("Close options").assertDoesNotExist()
    }

    @Test fun screensWithoutOptionsSaySo() {
        // The dial pad has no options until a number is entered.
        compose.onAllNodes(hasContentDescription("Phonebook")).onLast().performClick()
        compose.onNodeWithContentDescription("Dial pad").performClick()
        press(KeyEvent.KEYCODE_MENU)
        compose.onNodeWithText("No options here").assertIsDisplayed()
    }

    @Test fun theCallKeyNeverReachesTheSystemFromSidekit() {
        assertTrue(press(KeyEvent.KEYCODE_CALL))
    }
}
