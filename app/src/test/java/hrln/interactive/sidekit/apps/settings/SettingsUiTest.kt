package hrln.interactive.sidekit.apps.settings

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `launcher default row persists the selected layout`() {
        val prefs = SidekitPreferences(ApplicationProvider.getApplicationContext<Context>())
        prefs.listLayout = false
        compose.setContent { SidekitPreviewFrame { SettingsHome({}, {}) } }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("/tmp/sidekit-settings.png")
        compose.onNodeWithText("Launcher default").performClick()
        assertTrue(prefs.listLayout)
        compose.onNodeWithText("List").assertIsDisplayed()
    }
}
