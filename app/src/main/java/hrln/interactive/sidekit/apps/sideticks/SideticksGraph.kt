package hrln.interactive.sidekit.apps.sideticks

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
        val vm: SideticksViewModel = viewModel()
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
        )
    }
}
