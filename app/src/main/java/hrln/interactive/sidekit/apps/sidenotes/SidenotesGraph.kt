package hrln.interactive.sidekit.apps.sidenotes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Sidenotes.route
private val LIST = "$ROOT/list"
private const val ARG_ID = "id"
private val EDIT = "$ROOT/edit?$ARG_ID={$ARG_ID}"

private fun editRoute(id: Long) = "$ROOT/edit?$ARG_ID=$id"

fun NavGraphBuilder.sidenotesGraph(nav: NavHostController) {
    navigation(startDestination = LIST, route = ROOT) {
        composable(LIST) { entry ->
            val vm = entry.sidenotesViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            SidenotesListScreen(
                state = state,
                onOpenNote = { nav.navigateFrom(entry, editRoute(it.id)) },
                onNewNote = { nav.navigateFrom(entry, editRoute(NEW_NOTE_ID)) },
                onDelete = { vm.delete(it.id) },
                onUndoDelete = vm::undoDelete,
            )
        }
        composable(
            EDIT,
            arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType; defaultValue = NEW_NOTE_ID }),
        ) { entry ->
            val vm = entry.sidenotesViewModel(nav)
            val argId = entry.arguments?.getLong(ARG_ID) ?: NEW_NOTE_ID
            // A new note gets its id on first save; later saves and delete use it.
            var noteId by rememberSaveable { mutableLongStateOf(argId) }
            val initial = remember(argId) { vm.note(argId)?.body.orEmpty() }
            NoteEditorScreen(
                initialText = initial,
                isNew = argId == NEW_NOTE_ID,
                canDelete = noteId != NEW_NOTE_ID,
                onSave = { noteId = vm.save(noteId, it) },
                onClose = { vm.close(noteId, it); nav.popFrom(entry) },
                onDelete = { vm.delete(noteId); nav.popFrom(entry) },
            )
        }
    }
}

/** Ignores repeat taps that land while a transition is already under way. */
private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id == entry.id) navigate(route) { launchSingleTop = true }
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** One ViewModel per visit to the app, shared by its screens; notes themselves outlive it. */
@Composable
private fun NavBackStackEntry.sidenotesViewModel(nav: NavHostController): SidenotesViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(parent, factory = remember(context) { viewModelFactory { initializer { SidenotesViewModel(Sidenotes.repository(context)) } } })
}
