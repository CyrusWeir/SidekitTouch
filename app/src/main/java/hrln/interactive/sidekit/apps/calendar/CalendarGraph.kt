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

fun NavGraphBuilder.calendarGraph(nav: NavHostController) {
    composable(SubApp.Calendar.route) {
        CalendarPermission(write = false) {
            val context = LocalContext.current
            val repository = remember { DeviceCalendarRepository(context) }
            val vm: CalendarViewModel = viewModel(factory = remember { viewModelFactory { initializer { CalendarViewModel(repository) } } })
            val state by vm.state.collectAsStateWithLifecycle()
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(owner, repository) {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try { repository.changes().collect { vm.refresh() } }
                    finally { vm.stop() }
                }
            }
            CalendarScreen(state, vm::selectDay, { vm.mode(if (state.mode == CalendarMode.Month) CalendarMode.Day else CalendarMode.Month) },
                { nav.navigate("${SubApp.Calendar.route}/edit/new?date=${state.date}") },
                onEvent = { nav.navigate("${SubApp.Calendar.route}/edit/${it.id}?date=${state.date}") },
                onDate = vm::selectDate, onMove = vm::move, onAgenda = { vm.mode(CalendarMode.Agenda) }, onRefresh = vm::refresh)
        }
    }
    composable("${SubApp.Calendar.route}/edit/{id}?date={date}") { entry ->
        CalendarPermission(write = true) {
            CalendarEditor(entry.arguments?.getString("id")?.toLongOrNull(),
                runCatching { LocalDate.parse(entry.arguments?.getString("date")) }.getOrDefault(LocalDate.now()),
                onClose = { nav.popBackStack() })
        }
    }
}

@Composable
private fun CalendarPermission(write: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permission = if (write) Manifest.permission.WRITE_CALENDAR else Manifest.permission.READ_CALENDAR
    var granted by remember { mutableStateOf(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        denied = !granted
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED } }
    if (granted) content()
    else SidekitScaffold(title = "Calendar", actions = listOf(SideAction("allow", "Allow") {
        request.launch(if (write) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) else arrayOf(Manifest.permission.READ_CALENDAR))
    }), options = if (denied) listOf(SideAction("settings", "Settings") { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) else emptyList()) { padding ->
        val list = rememberLazyListState()
        SidekitList(padding, state = list) { item { SidekitItem(if (write) "Allow calendar access to save and delete events." else "Allow calendar access to see your events.", Modifier.listFade(list, 0)) } }
    }
}
