package hrln.interactive.sidekit.apps.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview
import java.text.NumberFormat
import java.util.Locale

data class StepsToday(val steps: Int, val goal: Int) {
    /** Share of the goal reached, clamped to 0..1 (0 when there is no goal). */
    val progress: Float get() = if (goal <= 0) 0f else (steps.toFloat() / goal).coerceIn(0f, 1f)
    val stepsText: String get() = formatCount(steps)
    val goalText: String get() = "/ ${formatCount(goal)}"
}

data class HealthMetric(val label: String, val value: String)

data class HealthSummary(val steps: StepsToday, val metrics: List<HealthMetric>)

private fun formatCount(n: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

object HealthSampleData {
    // Placeholder content from "Health / Home" (96:764).
    val summary = HealthSummary(
        steps = StepsToday(steps = 6420, goal = 8000),
        metrics = listOf(HealthMetric("Heart rate", "68 bpm"), HealthMetric("Last sleep", "7 hr 42 min")),
    )
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.healthGraph(nav: NavHostController) {
    val root = SubApp.Health.route
    composable(root) { LiveHealth("home", { nav.navigate("$root/history") }, { nav.navigate("$root/goals") }, { nav.popBackStack() }) }
    composable("$root/history") { LiveHealth("history", {}, {}, { nav.popBackStack() }) }
    composable("$root/goals") { LiveHealth("goals", {}, {}, { nav.popBackStack() }) }
}

/** "Health / Home" (96:764). "← History" and "Goals →" have no mocked destination. */
@Composable
fun HealthScreen(summary: HealthSummary, onHistory: () -> Unit = {}, onGoals: () -> Unit = {}) {
    SidekitScaffold(
        title = "Health",
        actions = dualArrowActions("History", "Goals", onHistory, onGoals),
    ) { padding ->
        EverydayContent(padding) {
            StepsCard(summary.steps)
            summary.metrics.forEach { SidekitItem(it.label, secondary = it.value) }
        }
    }
}

/** "Daily steps and goal" (96:817): 171 high, a 400 x 10 goal bar at y 137. */
@Composable
internal fun StepsCard(steps: StepsToday) {
    val colors = SidekitTheme.colors
    SidekitTile(Modifier.fillMaxWidth().height(171.dp), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.padding(start = SidekitTheme.spacing.itemPadding, end = SidekitTheme.spacing.itemPadding, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SidekitText("Steps today", style = SidekitTheme.typography.caption)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SidekitText(steps.stepsText, style = SidekitTheme.typography.numeric)
                SidekitText(steps.goalText, style = SidekitTheme.typography.caption, color = colors.surfaceA6)
            }
            val barShape = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(barShape)
                    .background(colors.surfaceA1)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(steps.progress, 0f..1f) },
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(steps.progress).clip(barShape).background(colors.primaryA1))
            }
        }
    }
}

@SidephonePreview
@Composable
private fun HealthPreview() = SidekitPreviewFrame { HealthScreen(HealthSampleData.summary) }
