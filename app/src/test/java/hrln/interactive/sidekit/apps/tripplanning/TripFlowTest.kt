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
        compose.onNodeWithText("Plan again").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Home → Park").assertIsDisplayed()
    }
    @Test fun selectNswStopsFindJourneySaveAndReadItsLegs() {
        val api = NswTransit(hrln.interactive.sidekit.apps.integrations.JsonRequest { url, _, body ->
            when {
                body != null -> org.json.JSONObject(javaClass.getResource("/transit/journeys.json")!!.readText())
                url.contains("name=Central") -> org.json.JSONObject("{locations:[{id:'200060',name:'Central, Sydney',type:'stop'}]}")
                else -> org.json.JSONObject("{locations:[{id:'200070',name:'Town Hall, Sydney',type:'stop'}]}")
            }
        })
        compose.setContent {
            val nav = rememberNavController()
            SidekitPreviewFrame { NavHost(nav, startDestination = SubApp.TripPlanning.route) { liveTripsGraph(nav, api) } }
        }
        compose.waitUntil(5_000) { TripRepository.get(compose.activity).store.state.value != null }
        compose.onNodeWithText("Plan").performClick()
        compose.onNodeWithText("From").performTextInput("Central")
        compose.onNodeWithText("Find From stop").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Central, Sydney").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Central, Sydney").performClick()
        compose.onNodeWithText("To").performScrollTo().performTextInput("Town Hall")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Find To stop"))
        compose.onNodeWithText("Find To stop").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Town Hall, Sydney").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Town Hall, Sydney").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Find journeys"))
        compose.onNodeWithText("Find journeys").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("12:02–12:05 · T8").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("12:02–12:05 · T8").performClick()
        compose.onNodeWithText("Central, Sydney → Town Hall, Sydney").performScrollTo().performClick()
        compose.onNodeWithText("T8: Platform 17 → Platform 6 · 12:02–12:05 · Scheduled").assertIsDisplayed()
    }

}
