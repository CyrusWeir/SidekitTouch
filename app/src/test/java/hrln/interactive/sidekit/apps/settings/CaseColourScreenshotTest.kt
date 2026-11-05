package hrln.interactive.sidekit.apps.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.maps.MapsSampleData
import hrln.interactive.sidekit.apps.maps.MapsScreen
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitColors
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Before/after demo of the green-accent commit clash on Maps' Search + Go bar. "Before" paints the
 * accent without the success override (two greens); "after" uses [CaseColour.palette].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaseColourScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, colors: SidekitColors) {
        compose.setContent {
            SidekitTheme(colors = colors) { SidekitCanvas { MapsScreen(MapsSampleData.destination) } }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun sageBefore() {
        val accent = CaseColour.Sage.color
        shoot(
            "case_colour_sage_before",
            SidekitColors(
                primaryA1 = accent,
                primaryA2 = lerp(accent, Color.White, 0.12f),
                primaryA3 = lerp(accent, Color.White, 0.24f),
            ),
        )
    }

    @Test fun sageAfter() = shoot("case_colour_sage_after", CaseColour.Sage.palette())
}
