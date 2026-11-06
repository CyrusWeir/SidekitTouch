package hrln.interactive.sidekit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.launcher.BuiltinEntry
import hrln.interactive.sidekit.launcher.LauncherEntry
import hrln.interactive.sidekit.launcher.LauncherLayout
import hrln.interactive.sidekit.launcher.LauncherScreen
import hrln.interactive.sidekit.launcher.LauncherTags
import hrln.interactive.sidekit.launcher.LauncherUiState
import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherUiTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(LauncherUiState(time = "9:19"))
    private val opened = mutableListOf<SubApp>()
    private val home: List<LauncherEntry> = SubApp.entries.map { BuiltinEntry(it) }
    private val dock: List<LauncherEntry> = listOf(SubApp.Mail, SubApp.Photos, SubApp.Sidenotes, SubApp.Sideticks).map { BuiltinEntry(it) }

    private fun launch() = compose.setContent {
        SidekitPreviewFrame {
            LauncherScreen(
                state = state,
                onOpenApp = { opened += it },
                onToggleLayout = {
                    state = state.copy(layout = if (state.layout == LauncherLayout.Grid) LauncherLayout.List else LauncherLayout.Grid)
                },
                home = home,
                dock = dock,
            )
        }
    }

    @Test fun showsClockGridAndDock() {
        launch()
        compose.onNodeWithText("9:19").assertIsDisplayed()
        compose.onNodeWithTag(LauncherTags.GRID).assertIsDisplayed()
        // The configured dock is the shared action bar: its buttons sit alongside the matching grid tiles.
        dock.forEach { compose.onAllNodesWithContentDescription(it.label).assertCountEquals(2) }
    }

    @Test fun dockButtonsOpenTheirApp() {
        launch()
        compose.onAllNodesWithContentDescription("Sidenotes").onLast().performClick()
        assertEquals(listOf(SubApp.Sidenotes), opened)
    }

    @Test fun tappingAGridTileOpensThatApp() {
        launch()
        compose.onNodeWithContentDescription("Translate").performClick()
        assertEquals(listOf(SubApp.Translate), opened)
    }

    @Test fun longPressingTheClockSwitchesToTheListView() {
        launch()
        compose.onNodeWithTag(LauncherTags.CLOCK).performTouchInput { longClick() }
        compose.onNodeWithTag(LauncherTags.LIST).assertIsDisplayed()
        compose.onNodeWithText("Sideticks").performClick()
        assertEquals(listOf(SubApp.Sideticks), opened)
    }
}
