package hrln.interactive.sidekit.apps.health

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun setGoalAndOpenHistoryWithoutPermission() {
        compose.setContent {
            val nav = rememberNavController()
            SidekitPreviewFrame { NavHost(nav, startDestination = SubApp.Health.route) { healthGraph(nav) } }
        }
        compose.waitUntil(5_000) { HealthRepository.get(compose.activity).store.state.value != null }
        compose.onNodeWithText("Goals").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("9000")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("/ 9,000").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("No samples yet").assertIsDisplayed()
    }
}
