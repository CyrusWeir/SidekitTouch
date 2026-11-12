package hrln.interactive.sidekit.onboarding

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.settings.*
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.input.*
import hrln.interactive.sidekit.design.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = SidekitPreferences(context)
    private var finished = false
    private fun launch(step: Int = 0, module: KeypadModule = KeypadModule.T9) {
        context.getSharedPreferences("sidekit_settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs.setupStep = step
        compose.setContent {
            val colour = rememberCaseColour(prefs)
            val scope = rememberCoroutineScope()
            val keypad = remember { Keypad.forTesting(scope, T9Words.Empty, module) }
            SidekitTheme(colors = colour?.palette() ?: SidekitColors()) {
                SidekitCanvas(keypad = keypad) { OnboardingScreen(onFinish = { finished = true }) }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
    }
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage("screenshots/onboarding/$name.png")
    }

    @Test fun `setup saves choices and only completes on Finish`() {
        launch()
        shot("01-home")
        compose.onNodeWithText("Next").performClick()
        assertEquals(1, prefs.setupStep)
        assertFalse(prefs.setupComplete)
        compose.onNodeWithContentDescription("Lime").performClick()
        assertEquals(CaseColour.Lime, prefs.caseColour)
        shot("02-colour")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("List").performClick()
        assertTrue(prefs.listLayout)
        shot("03-layout")
        compose.onNodeWithText("Next").performClick()
        shot("03b-hide-apps")
        compose.onNodeWithText("Next").performClick()
        shot("04-permissions")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Numbers").performClick()
        assertEquals(T9Mode.Numeric, prefs.inputMode)
        shot("05-typing")
        compose.onNodeWithText("Next").performClick()
        shot("06-t9")
        compose.onNodeWithText("Finish").performClick()
        assertTrue(finished)
        assertTrue(prefs.setupComplete)
        assertEquals(0, prefs.setupStep)
    }

    @Test fun `resumes at saved step and can go back`() {
        launch(2)
        compose.onNodeWithText("Your home screen").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, prefs.setupStep)
        compose.onNodeWithText("Make it yours").assertIsDisplayed()
    }

    @Test fun `current keypad is first and other demos do not change it`() {
        launch(6, KeypadModule.Qwerty)
        compose.onNodeWithText("1 / 4 · Mini QWERTY").assertIsDisplayed()
        compose.onNodeWithText("aA").performScrollTo().performClick()
        compose.onNodeWithText("Tap to change letter case. Hold to switch letters / numbers.").assertExists()
        shot("07-qwerty")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Skip / next keypad"))
        compose.onNodeWithText("Skip / next keypad").performClick()
        compose.onNodeWithText("2 / 4 · T9").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Skip / next keypad"))
        compose.onNodeWithText("Skip / next keypad").performClick()
        compose.onNodeWithText("3 / 4 · Sundial").assertExists()
        compose.onNodeWithText("Meet your keypad").assertIsDisplayed()
        compose.onNodeWithText("3 / 4 · Sundial").performScrollTo()
        shot("08-sundial")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Skip / next keypad"))
        compose.onNodeWithText("Skip / next keypad").performClick()
        compose.onNodeWithText("4 / 4 · Game controller").performScrollTo()
        shot("09-controller")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Mini QWERTY is your current selection.", substring = true))
        compose.onNodeWithText("Mini QWERTY is your current selection.", substring = true).assertExists()
    }

    @Test fun `Sundial demo follows the physical ring`() {
        assertEquals(
            listOf(
                listOf("←", "↑", "→"),
                listOf("Previous", "Play / pause", "Next"),
                listOf("Tab", "↓", "Enter")
            ),
            demoRows(KeypadModule.Sundial).map { row -> row.map { it.label } }
        )
    }

    @Test fun `access status reflects grants and revocations`() {
        val camera = setupAccess().first { it.title == "Camera" }
        val app = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        app.denyPermissions(android.Manifest.permission.CAMERA)
        assertFalse(camera.enabled(context))
        app.grantPermissions(android.Manifest.permission.CAMERA)
        assertTrue(camera.enabled(context))
        app.denyPermissions(android.Manifest.permission.CAMERA)
        assertFalse(camera.enabled(context))
    }

    @Test fun `each module appears once with the current one first`() {
        KeypadModule.entries.forEach { module ->
            assertEquals(module, orderedModules(module).first())
            assertEquals(KeypadModule.entries.toSet(), orderedModules(module).toSet())
            assertEquals(4, orderedModules(module).size)
        }
    }

    @Test
    @Config(sdk = [31])
    fun `Android 12 uses storage access without newer permission prompts`() {
        val permissions = setupAccess().flatMap { it.permissions }
        assertTrue(android.Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse(android.Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertFalse(android.Manifest.permission.READ_MEDIA_IMAGES in permissions)
    }

    @Test fun `Android 15 uses granular media access and full screen alarm settings`() {
        val entries = setupAccess()
        assertTrue(entries.any { it.title == "Full-screen alarms" })
        assertTrue(entries.any { android.Manifest.permission.READ_MEDIA_IMAGES in it.permissions })
        assertFalse(entries.any { android.Manifest.permission.READ_EXTERNAL_STORAGE in it.permissions })
    }
}
