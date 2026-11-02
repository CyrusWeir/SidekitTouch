package hrln.interactive.sidekit.apps.sideweather

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

/** Renders the Sideweather screens at 480 x 640 to app/screenshots/ for comparison with the Figma frames. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SideweatherScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val data = SideweatherSampleData

    @Test fun home() = shoot("sideweather_94-322") { SideweatherHomeScreen(data.current, data.summary, {}, {}) }

    @Test fun hourly() = shoot("sideweather_hourly_94-385") { ForecastScreen("Hourly forecast", data.hourly, {}, {}) }

    @Test fun fiveDay() = shoot("sideweather_5day_94-447") { ForecastScreen("5-day forecast", data.fiveDay, {}, {}) }

    @Test fun locations() = shoot("sideweather_locations_94-509") { LocationsScreen(data.locations, data.locationHint, {}, {}) }
}
