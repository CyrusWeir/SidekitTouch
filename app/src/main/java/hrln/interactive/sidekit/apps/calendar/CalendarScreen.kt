package hrln.interactive.sidekit.apps.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.apps.everyday.InfoStrip
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.calendarGraph(nav: NavHostController) {
    composable(SubApp.Calendar.route) {
        val vm: CalendarViewModel = viewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        CalendarScreen(state, onSelectDay = vm::selectDay)
    }
}

/** "Calendar / Home" (96:420). "← Month" and "New →" have no mocked destination. */
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    onSelectDay: (Int) -> Unit,
    onMonth: () -> Unit = {},
    onNew: () -> Unit = {},
) {
    SidekitScaffold(
        title = "Calendar",
        actions = dualArrowActions("Month", "New", onMonth, onNew),
    ) { padding ->
        EverydayContent(padding) {
            InfoStrip(state.month, height = 62.dp, top = 12.dp)
            WeekStrip(state.week, state.selectedIndex, onSelectDay)
            state.events.forEach { SidekitItem(title = it.time, secondary = it.title) }
        }
    }
}

/** "Week of 2 November" (96:478): seven 100-high day items, the selected one in primary. */
@Composable
private fun WeekStrip(week: List<CalendarDay>, selectedIndex: Int, onSelectDay: (Int) -> Unit) {
    SidekitItemRow {
        week.forEachIndexed { index, day ->
            val selected = index == selectedIndex
            SidekitTile(
                modifier = Modifier
                    .weight(1f)
                    .height(100.dp)
                    .semantics { this.selected = selected },
                color = if (selected) SidekitTheme.colors.primaryA1 else SidekitTheme.colors.surfaceA3,
                onClick = { onSelectDay(index) },
                onClickLabel = "${day.weekday} ${day.date}",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SidekitText(day.weekday, style = SidekitTheme.typography.caption)
                    SidekitText(day.date.toString(), style = SidekitTheme.typography.caption)
                }
            }
        }
    }
}

@SidephonePreview
@Composable
private fun CalendarPreview() = SidekitPreviewFrame { CalendarScreen(CalendarUiState(), {}) }

@SidephonePreview
@Composable
private fun CalendarEmptyDayPreview() = SidekitPreviewFrame { CalendarScreen(CalendarUiState(selectedIndex = 3), {}) }
