package hrln.interactive.sidekit.apps.tripplanning

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.apps.everyday.InfoStrip
import hrln.interactive.sidekit.apps.everyday.TopAlignedRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class Departure(val service: String, val destination: String, val dueIn: String) {
    val title: String get() = "$service · $destination"
}

object TripPlanningSampleData {
    // Placeholder content from "Trip Planning / Home" (96:1022).
    const val stop = "Central · Next departures"
    val departures = listOf(
        Departure("Bus 373", "Coogee", "4 min"),
        Departure("Tram L2", "Randwick", "7 min"),
        Departure("Train T1", "Hornsby", "12 min"),
    )
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.tripPlanningGraph(nav: NavHostController) {
    composable(SubApp.TripPlanning.route) { TripPlanningScreen(TripPlanningSampleData.stop, TripPlanningSampleData.departures) }
}

/** "Trip Planning / Home" (96:1022): 99-high departure rows. "← Saved" and "Plan →" aren't mocked. */
@Composable
fun TripPlanningScreen(
    stop: String,
    departures: List<Departure>,
    onSaved: () -> Unit = {},
    onPlan: () -> Unit = {},
) {
    SidekitScaffold(
        title = "Trip Planning",
        actions = dualArrowActions("Saved", "Plan", onSaved, onPlan),
    ) { padding ->
        EverydayContent(padding) {
            InfoStrip(stop, height = CompactRowHeight, top = 14.dp)
            departures.forEach {
                TopAlignedRow(it.title, it.dueIn, height = 99.dp, top = 9.dp, titleStyle = SidekitTheme.typography.label)
            }
        }
    }
}

@SidephonePreview
@Composable
private fun TripPlanningPreview() = SidekitPreviewFrame { TripPlanningScreen(TripPlanningSampleData.stop, TripPlanningSampleData.departures) }
