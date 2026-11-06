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

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    @Test fun `case swatches update the theme selection and saved preference`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SidekitPreferences(context)
        prefs.caseColour = null
        var renderedColour = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            val selected = rememberCaseColour(prefs)
            hrln.interactive.sidekit.design.theme.SidekitTheme(
                colors = selected?.palette() ?: hrln.interactive.sidekit.design.theme.SidekitColors(),
            ) {
                renderedColour = hrln.interactive.sidekit.design.theme.SidekitTheme.colors.primaryA1
                hrln.interactive.sidekit.design.canvas.SidekitCanvas { CaseColourScreen {} }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val expected = listOf(0xFFA2F49F, 0xFFF68A02, 0xFF82C7F3, 0xFFF575D1, 0xFF90C190, 0xFFAE99D1)
        CaseColour.entries.forEachIndexed { index, colour ->
            compose.onNodeWithContentDescription(colour.label).performClick()
            compose.onNodeWithContentDescription(colour.label).assertIsSelected()
            CaseColour.entries.filter { it != colour }.forEach {
                compose.onNodeWithContentDescription(it.label).assertIsNotSelected()
            }
            compose.runOnIdle {
                assertEquals(androidx.compose.ui.graphics.Color(expected[index]), renderedColour)
                assertEquals(colour, SidekitPreferences(context).caseColour)
            }
        }
        compose.onRoot().captureRoboImage("/tmp/sidekit-case-colour.png")
        compose.onNodeWithText("Original blue").performScrollTo().performClick()
        compose.runOnIdle {
            assertNull(SidekitPreferences(context).caseColour)
            assertEquals(androidx.compose.ui.graphics.Color(0xFF2C65E0), renderedColour)
        }
    }

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

    @Test fun `system settings live on their own page`() {
        var opened = 0
        compose.setContent { SidekitPreviewFrame { SettingsHome({}, {}, onSystem = { opened++ }) } }
        compose.mainClock.advanceTimeBy(1000)
        // Device rows are gone from the settings home; one entry leads to them instead.
        SettingsUiState().rows.forEach { compose.onNodeWithText(it.label).assertDoesNotExist() }
        scrollTo("System settings")
        compose.onNodeWithText("System settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun `system settings page lists the device settings`() {
        compose.setContent { SidekitPreviewFrame { SystemSettingsScreen {} } }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("/tmp/sidekit-system-settings.png")
        SettingsUiState().rows.forEach { scrollTo(it.label) }
    }
}
