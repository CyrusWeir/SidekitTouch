package hrln.interactive.sidekit.apps.sidenotes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Frames from the middle of Sidenotes' motion, for eyeballing the morph and swipe. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidenotesMotionScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionBarSplitsForUndo() {
        var deleted by mutableStateOf<Note?>(null)
        compose.setContent {
            SidekitPreviewFrame {
                SidenotesListScreen(SidenotesUiState(SidenotesSampleData.notes, recentlyDeleted = deleted), {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        deleted = SidenotesSampleData.notes.first()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.onRoot().captureRoboImage("screenshots/motion/sidenotes_undo_split_64ms.png")
        compose.mainClock.advanceTimeBy(64)
        compose.onRoot().captureRoboImage("screenshots/motion/sidenotes_undo_split_128ms.png")
    }

    @Test fun editorBarMorphsToConfirmDelete() {
        compose.setContent {
            SidekitPreviewFrame { NoteEditorScreen(SidenotesSampleData.notes[1].body, isNew = false, canDelete = true, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(414f, 574f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("screenshots/motion/editor_confirm_delete_100ms.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/motion/editor_confirm_delete.png")
    }

    @Test fun swipeRevealsDelete() {
        compose.setContent {
            SidekitPreviewFrame { SidenotesListScreen(SidenotesUiState(SidenotesSampleData.notes), {}, {}, {}, {}) }
        }
        compose.onAllNodesWithText("Tax No")[0].performTouchInput { swipeLeft(startX = 400f, endX = 250f) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/motion/sidenotes_swipe_open.png")
    }
}
