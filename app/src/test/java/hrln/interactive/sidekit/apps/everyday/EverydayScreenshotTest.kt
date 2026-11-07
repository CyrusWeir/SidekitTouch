package hrln.interactive.sidekit.apps.everyday

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.banking.BankingSampleData
import hrln.interactive.sidekit.apps.banking.BankingScreen
import hrln.interactive.sidekit.apps.calendar.CalendarScreen
import hrln.interactive.sidekit.apps.calendar.CalendarUiState
import hrln.interactive.sidekit.apps.compass.CompassSampleData
import hrln.interactive.sidekit.apps.compass.CompassScreen
import hrln.interactive.sidekit.apps.files.FilesSampleData
import hrln.interactive.sidekit.apps.files.FilesScreen
import hrln.interactive.sidekit.apps.health.HealthSampleData
import hrln.interactive.sidekit.apps.health.HealthScreen
import hrln.interactive.sidekit.apps.mail.MailSampleData
import hrln.interactive.sidekit.apps.mail.MailScreen
import hrln.interactive.sidekit.apps.maps.MapsSampleData
import hrln.interactive.sidekit.apps.maps.MapsScreen
import hrln.interactive.sidekit.apps.settings.SettingsScreen
import hrln.interactive.sidekit.apps.settings.SettingsUiState
import hrln.interactive.sidekit.apps.tripplanning.TripPlanningSampleData
import hrln.interactive.sidekit.apps.tripplanning.TripPlanningScreen
import hrln.interactive.sidekit.apps.webbrowser.WebBrowserSampleData
import hrln.interactive.sidekit.apps.webbrowser.WebBrowserScreen
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Renders the "Sidephone · Everyday apps" screens (96:419) at 480 x 640 to app/screenshots/. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EverydayScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    // Pinned date: the month grid highlights "today", so a live clock would re-record this
    // screenshot every midnight and keep the working tree permanently dirty.
    @Test fun calendar() = shoot("calendar_96-420") {
        CalendarScreen(CalendarUiState(date = LocalDate.of(2026, 11, 5)), {})
    }
    @Test fun mail() = shoot("mail_96-499") { MailScreen(MailSampleData.summary, MailSampleData.inbox) }
    @Test fun maps() = shoot("maps_96-563") { MapsScreen(MapsSampleData.destination) }
    @Test fun compass() = shoot("compass_96-629") { CompassScreen(CompassSampleData.reading) }
    @Test fun files() = shoot("files_96-696") { FilesScreen(FilesSampleData.locations) }
    @Test fun health() = shoot("health_96-764") { HealthScreen(HealthSampleData.summary) }
    @Test fun webBrowser() = shoot("webbrowser_96-830") { WebBrowserScreen(WebBrowserSampleData.page) }
    @Test fun settings() = shoot("settings_96-891") { SettingsScreen(SettingsUiState().rows, {}) }
    @Test fun banking() = shoot("banking_96-959") { BankingScreen(BankingSampleData.account, BankingSampleData.transactions) }
    @Test fun tripPlanning() = shoot("tripplanning_96-1022") { TripPlanningScreen(TripPlanningSampleData.stop, TripPlanningSampleData.departures) }
}
