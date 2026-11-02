package hrln.interactive.sidekit.apps.translate

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

/** Renders the Translate screens at 480 x 640 to app/screenshots/ for comparison with the Figma frames. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslateScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun base() = shoot("translate_1-910") { TranslateScreen(TranslateUiState(), {}) }

    @Test fun talking() = shoot("translate_talking_1-837") { TranslateScreen(TranslateUiState(talking = TranslateSide.Target), {}) }

    @Test fun largePrompt() = shoot("translate_large_1-872") {
        TranslateScreen(TranslateUiState().toggleMic(TranslateSide.Source).toggleMic(TranslateSide.Source), {})
    }
}
