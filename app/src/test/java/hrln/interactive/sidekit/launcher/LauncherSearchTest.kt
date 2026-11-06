package hrln.interactive.sidekit.launcher

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.settings.*
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.input.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherSearchTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var keypad: Keypad
    private val chrome = ChromeState()
    private fun launch(module: KeypadModule, layout: LauncherLayout = LauncherLayout.List) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            keypad = remember { Keypad.forTesting(scope, T9Words.parse("mail\nmaps\nmusic"), module) }
            SidekitTheme {
                SidekitCanvas(keypad = keypad, chrome = chrome) {
                    val portedApps = listOf(PortedApp("test.matrix", "Matrix"), PortedApp("test.hidden", "Masked"))
                    LauncherScreen(
                        LauncherUiState(layout = layout), {}, {},
                        home = SubApp.entries.map { BuiltinEntry(it) } + portedApps.map { PortedEntry(it) },
                        hiddenApps = setOf(SubApp.Maps.visibilityId(), SubApp.Sidemessage.visibilityId(), "ported:test.hidden"),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
    }
    private fun key(code: Int) {
        compose.runOnIdle { keypad.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
        compose.runOnIdle { keypad.dispatch(KeyEvent(KeyEvent.ACTION_UP, code)) }
        compose.mainClock.advanceTimeBy(200)
    }
    private fun checkMatchesAndClear(name: String) {
        compose.onNode(hasText("Mail") and hasAnyAncestor(hasTestTag(LauncherTags.LIST))).assertIsDisplayed()
        compose.onNodeWithText("Matrix").assertIsDisplayed()
        compose.onNode(hasText("Maps") and hasAnyAncestor(hasTestTag(LauncherTags.LIST))).assertDoesNotExist()
        compose.onNodeWithText("Masked").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sidenotes").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1000)
        val clear = compose.onNodeWithContentDescription("Clear app search")
        clear.assertIsDisplayed()
        val bounds = clear.fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width, bounds.height, 1f)
        compose.onRoot().captureRoboImage("screenshots/onboarding/$name.png")
        clear.performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("Sidenotes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear app search").assertDoesNotExist()
        compose.onNodeWithTag(LauncherTags.SEARCH).assertTextEquals("")
    }
    @Test fun `T9 typing starts from home filters apps and clear restores dock`() {
        launch(KeypadModule.T9)
        key(KeyEvent.KEYCODE_6)
        key(KeyEvent.KEYCODE_2)
        checkMatchesAndClear("10-search-t9")
    }
    @Test fun `QWERTY typing filters apps with a permanent square clear control`() {
        launch(KeypadModule.Qwerty)
        key(KeyEvent.KEYCODE_M)
        key(KeyEvent.KEYCODE_A)
        checkMatchesAndClear("11-search-qwerty")
    }
    @Test fun `grid empty results retain clear and hidden apps stay absent after clearing`() {
        launch(KeypadModule.Qwerty, LauncherLayout.Grid)
        compose.onAllNodesWithContentDescription("Sidemessage").assertCountEquals(0)
        key(KeyEvent.KEYCODE_Z)
        compose.onNodeWithText("No matching apps").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear app search").assertIsDisplayed().performClick()
        compose.onNodeWithTag(LauncherTags.GRID).assertExists()
        compose.onAllNodesWithContentDescription("Sidemessage").assertCountEquals(0)
    }
    @Test fun `grid search includes visible built-in and ported apps`() {
        launch(KeypadModule.Qwerty, LauncherLayout.Grid)
        key(KeyEvent.KEYCODE_M)
        key(KeyEvent.KEYCODE_A)
        compose.onNodeWithContentDescription("Mail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Maps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Masked").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage("screenshots/onboarding/12-search-grid.png")
    }
    @Test fun `clear remains square and rightmost in typing options`() {
        launch(KeypadModule.T9)
        key(KeyEvent.KEYCODE_6)
        compose.runOnIdle { chrome.toggleOptions() }
        compose.mainClock.advanceTimeBy(1000)
        val clear = compose.onNodeWithContentDescription("Clear app search")
        clear.assertIsDisplayed()
        val clearBounds = clear.fetchSemanticsNode().boundsInRoot
        val closeBounds = compose.onNodeWithContentDescription("Close options").fetchSemanticsNode().boundsInRoot
        assertEquals(clearBounds.width, clearBounds.height, 1f)
        assertTrue(clearBounds.left >= closeBounds.right)
        clear.performClick()
        compose.onNodeWithContentDescription("Close options").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sidenotes").assertIsDisplayed()
    }
    @Test fun `visibility choices persist and can be restored`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SidekitPreferences(context)
        prefs.hiddenApps = emptySet()
        compose.setContent { SidekitTheme { SidekitCanvas { HiddenAppsScreen {} } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sidemessage"))
        compose.onNodeWithText("Sidemessage").performClick()
        assertTrue(SubApp.Sidemessage.visibilityId() in SidekitPreferences(context).hiddenApps)
        compose.onNodeWithText("Hidden").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage("screenshots/onboarding/13-hide-apps-settings.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Show all apps"))
        compose.onNodeWithText("Show all apps").performClick()
        assertTrue(prefs.hiddenApps.isEmpty())
    }
}
