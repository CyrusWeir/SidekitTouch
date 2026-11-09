package hrln.interactive.sidekit.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.R
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.launcher.LauncherScreen
import hrln.interactive.sidekit.launcher.LauncherUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dev marker is driven entirely by resources. Unit tests compile against `src/main`, i.e.
 * what a stable install shows, so these defaults are the guarantee that a release build can't
 * advertise itself as a dev build - and that the recorded screenshots keep their current frames.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevBuildPillTest {
    @get:Rule val compose = createComposeRule()

    private val description get() = "Development build"

    @Test fun stableBuildsCarryNoMarker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("", context.getString(R.string.build_label))
        assertFalse(context.resources.getBoolean(R.bool.is_dev_build))
    }

    @Test fun thePillIsSpokenAsOneLabel() {
        compose.setContent { SidekitTheme { DevBuildPill("DEV") } }
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    @Test fun theLauncherHeaderShowsNoPillInAStableBuild() {
        compose.setContent { SidekitPreviewFrame { LauncherScreen(LauncherUiState(), {}, {}) } }
        compose.onAllNodesWithContentDescription(description).assertCountEquals(0)
    }
}
