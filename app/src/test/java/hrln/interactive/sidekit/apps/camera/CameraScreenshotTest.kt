package hrln.interactive.sidekit.apps.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Renders the Camera screens at 480 x 640 to app/screenshots/ for comparison with the Figma frames. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun noFlash() = shoot("camera_71-1986") { CameraScreen(CameraUiState(), {}, {}, {}, {}) }

    @Test fun recording() = shoot("camera_recording_71-2114") {
        CameraScreen(CameraUiState(flashOn = true, recording = true, elapsedSeconds = 1), {}, {}, {}, {})
    }
}
