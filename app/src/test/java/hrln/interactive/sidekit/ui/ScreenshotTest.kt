package hrln.interactive.sidekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.sidemessage.ConversationScreen
import hrln.interactive.sidekit.apps.sidemessage.SidemessageInboxScreen
import hrln.interactive.sidekit.apps.sidemessage.SidemessageSampleData
import hrln.interactive.sidekit.apps.sidenotes.NoteEditorScreen
import hrln.interactive.sidekit.apps.sidenotes.SidenotesListScreen
import hrln.interactive.sidekit.apps.sidenotes.SidenotesSampleData
import hrln.interactive.sidekit.apps.sidenotes.SidenotesUiState
import hrln.interactive.sidekit.apps.sideticks.SideticksScreen
import hrln.interactive.sidekit.apps.sideticks.SideticksUiState
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.launcher.LauncherLayout
import hrln.interactive.sidekit.launcher.LauncherScreen
import hrln.interactive.sidekit.launcher.LauncherUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders key screens at the Sidephone's 480 x 640 px (robolectric.properties: w480dp-h640dp-mdpi)
 * to app/screenshots/ for side-by-side comparison with the Figma frames.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun launcher(state: LauncherUiState) =
        @Composable { LauncherScreen(state, {}, {}) }

    @Test fun launcherGrid() = shoot("launcher_grid_53-1129", launcher(LauncherUiState()))
    /**
     * The local "Sidephone" AVD reports 320 dpi (480 x 640 px = 240 x 320 dp). SidekitCanvas must
     * still lay out the full 480 x 640 Figma frame; compare with launcher_grid_53-1129.png.
     */
    @Config(qualifiers = "w240dp-h320dp-xhdpi")
    @Test fun launcherGridAtSidephoneAvdDensity() = shoot("launcher_grid_avd_320dpi", launcher(LauncherUiState()))

    @Test fun launcherList() = shoot("launcher_list_36-2", launcher(LauncherUiState(layout = LauncherLayout.List)))
    /** The same frame with Settings → Compact layout on: header 75 instead of 103, bar 100 instead of 112. */
    @Test fun launcherGridCompact() {
        compose.setContent {
            hrln.interactive.sidekit.design.theme.SidekitTheme(
                spacing = hrln.interactive.sidekit.design.theme.SidekitSpacing.Compact,
            ) { SidekitCanvas { LauncherScreen(LauncherUiState(), {}, {}) } }
        }
        compose.onRoot().captureRoboImage("screenshots/launcher_grid_compact.png")
    }
    @Test fun sidenotes() = shoot("sidenotes_1-278") { SidenotesListScreen(SidenotesUiState(SidenotesSampleData.notes), {}, {}, {}, {}) }
    @Test fun sidenotesUndo() = shoot("sidenotes_undo") {
        SidenotesListScreen(SidenotesUiState(SidenotesSampleData.notes.drop(1), recentlyDeleted = SidenotesSampleData.notes.first()), {}, {}, {}, {})
    }
    @Test fun note() = shoot("note_1-312") { NoteEditorScreen(SidenotesSampleData.notes.first().body, isNew = false, canDelete = true, {}, {}, {}) }
    @Test fun sideticks() = shoot("sideticks_1-564") { SideticksScreen(SideticksUiState(showTicked = true), {}, {}, {}, {}, {}, {}) }
    @Test fun sidemessage() = shoot("sidemessage_35-98") {
        SidemessageInboxScreen(SidemessageSampleData.inbox, true, {}, {}, {}, {}, {})
    }
    @Test fun conversation() = shoot("conversation_35-145") {
        ConversationScreen(SidemessageSampleData.conversation, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
