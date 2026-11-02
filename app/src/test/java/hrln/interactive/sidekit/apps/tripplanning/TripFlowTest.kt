package hrln.interactive.sidekit.apps.tripplanning

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun createTripOpenSavedDetailsAndEdit() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            SidekitPreviewFrame { NavHost(nav, startDestination = SubApp.TripPlanning.route) { tripPlanningGraph(nav) } }
        }
        compose.waitUntil(5_000) { TripRepository.get(compose.activity).store.state.value != null }
        compose.onNodeWithText("Plan").performClick()
        compose.onNodeWithText("From").performTextInput("Home")
        compose.onNodeWithText("To").performTextInput("Park")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Home → Park").assertIsDisplayed().performClick()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("To Park").assertIsDisplayed()
        compose.onNodeWithText("Directions").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Home → Park").assertIsDisplayed()
    }
}
