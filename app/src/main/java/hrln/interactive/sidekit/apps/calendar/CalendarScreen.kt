package hrln.interactive.sidekit.apps.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import java.time.*

@Composable
fun CalendarScreen(
    state: CalendarUiState, onSelectDay: (Int) -> Unit, onMonth: () -> Unit = {}, onNew: () -> Unit = {},
    onEvent: (CalendarEvent) -> Unit = {}, onDate: (LocalDate) -> Unit = {}, onMove: (Long) -> Unit = {},
    onAgenda: () -> Unit = {}, onRefresh: () -> Unit = {},
) {
    SidekitScaffold(title = "Calendar", actions = listOf(SideAction("view", if (state.mode == CalendarMode.Month) "Day" else "Month", onClick = onMonth), SideAction("new", "New", onClick = onNew)),
        options = listOf(SideAction("agenda", "Agenda", onClick = onAgenda), SideAction("today", "Today") { onDate(LocalDate.now()) })) { padding ->
        val list = rememberLazyListState()
        SidekitList(padding, state = list) {
            item { SidekitItemRow(Modifier.listFade(list, 0)) {
                SidekitTile(Modifier.weight(1f), onClick = { onMove(-1) }, onClickLabel = "Previous") { SidekitText("‹") }
                SidekitTile(Modifier.weight(4f)) { SidekitText(state.month, style = SidekitTheme.typography.caption) }
                SidekitTile(Modifier.weight(1f), onClick = { onMove(1) }, onClickLabel = "Next") { SidekitText("›") }
            } }
            if (state.mode == CalendarMode.Month) {
                val first = state.date.withDayOfMonth(1)
                val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
                (0..5).forEach { week -> item {
                    SidekitItemRow(Modifier.listFade(list, week + 1)) {
                        (0..6).forEach { day ->
                            val date = start.plusDays((week * 7 + day).toLong())
                            val hasEvents = state.events.any { event ->
                                val zone = if (event.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                                val begins = date.atStartOfDay(zone).toInstant().toEpochMilli()
                                event.start < date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() && event.end > begins
                            }
                            SidekitTile(Modifier.weight(1f).height(SidekitTheme.spacing.itemHeight),
                                color = if (date == state.date) SidekitTheme.colors.primaryA1 else SidekitTheme.colors.surfaceA3,
                                onClick = { onDate(date) }, onClickLabel = date.toString()) {
                                SidekitText(if (date.month == first.month) "${date.dayOfMonth}${if (hasEvents) "·" else ""}" else "", style = SidekitTheme.typography.caption)
                            }
                        }
                    }
                } }
            } else {
                item { WeekStrip(state.week, state.selectedIndex, onSelectDay, Modifier.listFade(list, 1)) }
                state.events.forEachIndexed { index, event -> item("${event.id}:${event.start}") {
                    val label = if (state.mode == CalendarMode.Agenda) "${Instant.ofEpochMilli(event.start).atZone(if (event.allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate()} ${event.time}" else event.time
                    SidekitItem(label, Modifier.listFade(list, index + 2), secondary = event.title, onClick = { onEvent(event) })
                } }
            }
            if (state.loading) item { SidekitItem("Loading events…") }
            else if (state.error != null) item { SidekitItem(state.error, onClick = onRefresh) }
            else if (state.events.isEmpty()) item { SidekitItem("No events", Modifier.listFade(list, 2)) }
        }
    }
}

@Composable
private fun WeekStrip(week: List<CalendarDay>, selectedIndex: Int, onSelectDay: (Int) -> Unit, modifier: Modifier) {
    SidekitItemRow(modifier) {
        week.forEachIndexed { index, day ->
            val selected = index == selectedIndex
            SidekitTile(Modifier.weight(1f).height(SidekitTheme.spacing.itemHeight).semantics { this.selected = selected },
                color = if (selected) SidekitTheme.colors.primaryA1 else SidekitTheme.colors.surfaceA3,
                onClick = { onSelectDay(index) }, onClickLabel = "${day.weekday} ${day.date}") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SidekitText(day.weekday, style = SidekitTheme.typography.caption)
                    SidekitText(day.date.toString(), style = SidekitTheme.typography.caption)
                }
            }
        }
    }
}
