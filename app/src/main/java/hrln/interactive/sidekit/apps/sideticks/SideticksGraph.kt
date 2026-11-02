package hrln.interactive.sidekit.apps.sideticks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.navigation.SubApp

fun NavGraphBuilder.sideticksGraph(@Suppress("UNUSED_PARAMETER") nav: NavHostController) {
    composable(SubApp.Sideticks.route) {
        val context = LocalContext.current
        val vm: SideticksViewModel = viewModel(factory = remember { viewModelFactory { initializer { SideticksViewModel(Sideticks.repository(context.applicationContext)) } } })
        var needsNotifications by remember { mutableStateOf(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { needsNotifications = !it }
        val state by vm.state.collectAsStateWithLifecycle()
        BackHandler(enabled = state.draft != null, onBack = vm::cancelAdding)
        SideticksScreen(
            state = state,
            onToggle = { vm.toggle(it.id) },
            onShowTickedChange = vm::setShowTicked,
            onClearTicked = vm::clearTicked,
            onNewTick = vm::startAdding,
            onDraftChange = vm::updateDraft,
            onConfirmDraft = vm::confirmDraft,
            onDelete = { vm.delete(listOf(it)) }, onUndo = vm::undoDelete, onDue = vm::setDue,
            onSort = vm::sort, onCancel = vm::cancelAdding, needsNotifications = needsNotifications,
            onNotifications = { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    }
}
