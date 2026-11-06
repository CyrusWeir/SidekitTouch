package hrln.interactive.sidekit.apps.apps

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.GraphicsMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `tap launches and options no longer offer dock Pin`() {
        val app = LaunchableApp(ComponentName("org.mozilla", "Main"), Process.myUserHandle(), "Firefox")
        var launched: LaunchableApp? = null
        val source = object : AppsSource {
            override val apps = MutableStateFlow(listOf(app))
            override suspend fun icon(app: LaunchableApp): Bitmap? = null
            override fun launch(app: LaunchableApp) { launched = app }
            override fun info(app: LaunchableApp) {}
        }
        val vm = AppsViewModel(source)
        lateinit var chrome: ChromeState
        compose.setContent { SidekitPreviewFrame { chrome = LocalChrome.current!!; AppsScreen(vm) {} } }
        compose.onRoot().captureRoboImage("/tmp/sidekit-apps.png")
        compose.onNodeWithText("Firefox").performClick()
        assertEquals(app, launched)
        compose.onNodeWithText("Firefox").performTouchInput { longClick() }
        compose.runOnIdle { chrome.toggleOptions() }
        // Dock membership is managed on the dedicated Settings → Arrange apps screen, so the Apps
        // screen no longer offers a misleading Pin that wrote a dead dockComponent stub.
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.onNodeWithText("App info").assertExists()
        compose.onNodeWithText("Uninstall").assertExists()
    }
}
