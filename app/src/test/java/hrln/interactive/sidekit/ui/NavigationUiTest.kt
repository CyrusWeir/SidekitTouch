package hrln.interactive.sidekit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import hrln.interactive.sidekit.apps.sidenotes.SidenotesTags
import hrln.interactive.sidekit.launcher.LauncherTags
import hrln.interactive.sidekit.navigation.LAUNCHER_ROUTE
import hrln.interactive.sidekit.navigation.SidekitNavHost
import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController

    private fun launch() = compose.setContent {
        nav = rememberNavController()
        SidekitPreviewFrame { SidekitNavHost(nav) }
    }

    private fun openFromDock(app: SubApp) =
        compose.onAllNodesWithContentDescription(app.label).onLast().performClick()

    @Test fun sidenotesWriteSaveSwipeDeleteUndoAndGoHome() {
        launch()
        openFromDock(SubApp.Sidenotes)
        compose.onNodeWithText("My Sidenotes").assertIsDisplayed()

        // New note: the single action morphs into "← My Sidenotes"; typing splits out Save.
        compose.onNodeWithContentDescription("New Sidenote").performClick()
        compose.onNodeWithTag(SidenotesTags.EDITOR).performTextInput("Groceries\nMilk")
        compose.onNodeWithContentDescription("Save note").performClick()
        compose.onNodeWithText("Groceries").assertIsDisplayed() // header follows the saved title
        compose.onNodeWithText("My Sidenotes").performClick() // "← My Sidenotes" action

        // Back on the list: swipe the row left to reveal delete, tap it, then undo.
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithText("Groceries").performTouchInput { swipeLeft(startX = right - 10f, endX = right - 200f) }
        compose.onNode(hasClickLabel("Delete Groceries")).performClick()
        compose.onNodeWithText("Groceries").assertDoesNotExist()
        compose.onNodeWithContentDescription("Undo delete").performClick()
        compose.onNodeWithText("Groceries").assertIsDisplayed()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(LauncherTags.GRID).assertIsDisplayed()
        assertEquals(LAUNCHER_ROUTE, nav.currentDestination?.route)
    }

    @Test fun sidemessageConversationRoundTrip() {
        launch()
        openFromDock(SubApp.Sidemessage)
        compose.onNodeWithText("Amelie").performClick()
        compose.onNodeWithText("Im free tn").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("New Message").assertIsDisplayed()
    }

    @Test fun undesignedAppsOpenThePlaceholderAndGoHome() {
        launch()
        openFromDock(SubApp.Phonebook)
        compose.onNodeWithText("Phonebook").assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithTag(LauncherTags.GRID).assertIsDisplayed()
    }

    @Test fun everyLauncherAppHasADestination() {
        launch()
        SubApp.entries.forEach { app ->
            compose.runOnIdle { nav.navigate(app.route) }
            compose.waitForIdle()
            assertTrue("${app.name} route missing", nav.currentBackStackEntry?.destination?.parent?.route == app.route || nav.currentDestination?.route == app.route)
            compose.runOnIdle { nav.popBackStack(LAUNCHER_ROUTE, inclusive = false) }
        }
    }
}

private fun hasClickLabel(label: String) =
    SemanticsMatcher("click label = $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
