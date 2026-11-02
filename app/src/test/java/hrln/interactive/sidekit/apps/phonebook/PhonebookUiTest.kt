package hrln.interactive.sidekit.apps.phonebook

import com.github.takahirom.roborazzi.captureRoboImage
import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.apps.sidemessage.SidemessageTags
import hrln.interactive.sidekit.navigation.SidekitNavHost
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class PhonebookUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() { Phonebook.sourceOverride = null }

    private fun launch() {
        compose.setContent {
            val nav = rememberNavController()
            SidekitPreviewFrame { SidekitNavHost(nav) }
        }
        compose.onAllNodesWithContentDescription("Phonebook").onLast().performClick()
    }

    @Test fun contactNumberOpensItsConversation() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_CONTACTS)
        Phonebook.sourceOverride = object : PhonebookSource {
            override fun contacts() = listOf(Contact(1, "Ada", listOf(PhoneNumber("0412345678", "Mobile"), PhoneNumber("0298765432", "Home"))))
            override fun calls(limit: Int) = emptyList<CallEntry>()
            override fun contactChanges() = emptyFlow<Unit>()
            override fun callChanges() = emptyFlow<Unit>()
        }
        launch()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Ada").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("/tmp/communication-phonebook.png")
        compose.onNodeWithText("Ada").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithContentDescription("Message 0298765432").performClick()
        compose.onNodeWithTag(SidemessageTags.CONVERSATION).assertIsDisplayed()
    }

    @Test fun dialPadOffersContextualCallPermission() {
        launch()
        compose.onNodeWithContentDescription("Dial pad").performClick()
        compose.onNodeWithContentDescription("Key 4").performClick()
        compose.onNodeWithContentDescription("Key 2").performClick()
        compose.onRoot().captureRoboImage("/tmp/communication-dial.png")
        compose.onNodeWithContentDescription("Call 42").performClick()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithText("Allow phone access to call 42. You can also use the system dialer from Options.").assertIsDisplayed()
    }
}
