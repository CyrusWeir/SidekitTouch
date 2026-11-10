package hrln.interactive.sidekit.apps.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.home.HomePreferences
import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Arrange apps smoke test (rendering). The edit-buffer logic is unit-tested directly
 * in [hrln.interactive.sidekit.home.HomeArrangerTest]; manipulating the chrome action bar through
 * compose clicks is unreliable, so the interesting behaviour lives at the state-holder level.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArrangeHomeTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanPrefs() {
        HomePreferences(context).reset()
        SidekitPreferences(context).hiddenApps = emptySet()
    }

    private fun arrange() {
        compose.setContent {
            SidekitTheme {
                SidekitCanvas {
                    ArrangeHomeScreen(back = {})
                }
            }
        }
        compose.waitForIdle()
    }

    /** The keypad is a hardware input device: clickable rows are focus targets in keyboard mode. */
    private fun arrangeWithKeypad() {
        compose.setContent {
            val keyboard = object : InputModeManager {
                override val inputMode = InputMode.Keyboard
                override fun requestInputMode(inputMode: InputMode) = true
            }
            CompositionLocalProvider(LocalInputModeManager provides keyboard) {
                SidekitTheme {
                    SidekitCanvas {
                        ArrangeHomeScreen(back = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `renders every app with the two-row keypad hint and dock labels`() {
        arrange()
        compose.onNodeWithText("Enter").assertIsDisplayed()
        compose.onNodeWithText(": Select App").assertIsDisplayed()
        compose.onNodeWithText("Up").assertIsDisplayed()
        compose.onNodeWithText("Down").assertIsDisplayed()
        compose.onNodeWithText(": Move App").assertIsDisplayed()
        compose.onAllNodesWithText("In dock").assertCountEquals(HomePreferences.DOCK_MAX)
        SubApp.dock.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        compose.onNodeWithText("Photos").assertExists()
    }

    @Test
    fun `move arrows appear only on the selected row`() {
        arrange()
        compose.onNodeWithContentDescription("Move Sidenotes up").assertDoesNotExist()
        compose.onNodeWithText("Sidenotes").performClick()
        compose.onNodeWithContentDescription("Move Sidenotes up").assertExists()
        compose.onNodeWithContentDescription("Move Sidenotes down").assertExists()
        compose.onNodeWithContentDescription("Move Photos up").assertDoesNotExist()
    }

    @Test
    fun `keypad walks the app list without selecting, then moves the selected app`() {
        arrangeWithKeypad()

        // Nothing is selected: Up/Down only move focus, exactly like the home app list.
        compose.onNodeWithText("Sidemessage").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Sidenotes").assertIsFocused()
        compose.onNodeWithContentDescription("Move Sidenotes up").assertDoesNotExist()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Sideticks").assertIsFocused()

        // Confirm selects the focused app; from then on Up/Down move it instead of moving focus.
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Move Sideticks up").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Sideticks").assertIsFocused()
        compose.onNodeWithContentDescription("Move Sideticks up").assertExists()

        // Confirm on the selected row deselects, and the keypad browses freely again.
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Move Sideticks up").assertDoesNotExist()
    }

    @Test
    fun `keypad can reach the hint above the first app`() {
        arrangeWithKeypad()
        compose.onNodeWithText("Sidemessage").assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNode(hasText("Enter") and hasAnyAncestor(isFocused())).assertExists()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Sidemessage").assertIsFocused()
    }

    @Test
    fun `hidden apps are omitted from the rearrangement list`() {
        SidekitPreferences(context).hiddenApps = setOf("builtin:Sidenotes")
        arrange()
        compose.onNodeWithText("Sidenotes").assertDoesNotExist()
        compose.onNodeWithText("Sidemessage").assertExists()
    }
}
