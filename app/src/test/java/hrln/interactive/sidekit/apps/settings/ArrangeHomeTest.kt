package hrln.interactive.sidekit.apps.settings

import android.content.Context
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

    @Test
    fun `renders every app with the four pinned dock apps marked In dock`() {
        arrange()
        compose.onNodeWithText("Select apps, then move them up or down. First 4 apps are also shown in the dock").assertIsDisplayed()
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
    fun `hidden apps are omitted from the rearrangement list`() {
        SidekitPreferences(context).hiddenApps = setOf("builtin:Sidenotes")
        arrange()
        compose.onNodeWithText("Sidenotes").assertDoesNotExist()
        compose.onNodeWithText("Sidemessage").assertExists()
    }
}
