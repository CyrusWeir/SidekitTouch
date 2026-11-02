package hrln.interactive.sidekit.apps.sidemessage

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Sidemessage.route
private val INBOX = "$ROOT/inbox"
private val THREAD = "$ROOT/thread/{contact}"

fun NavGraphBuilder.sidemessageGraph(nav: NavHostController) {
    navigation(startDestination = INBOX, route = ROOT) {
        composable(INBOX) { entry ->
            val vm = entry.sidemessageViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            SidemessageInboxScreen(
                pinned = state.pinned,
                threads = state.threads,
                onOpen = { nav.navigate("$ROOT/thread/${Uri.encode(it)}") },
                // No mock for composing to a new contact yet.
                onNewMessage = {},
            )
        }
        composable(THREAD) { entry ->
            val vm = entry.sidemessageViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val contact = entry.arguments?.getString("contact").orEmpty()
            val leave = { vm.stopTyping(); nav.popBackStack(); Unit }
            BackHandler(enabled = state.draft != null, onBack = vm::stopTyping)
            ConversationScreen(
                conversation = state.conversation(contact),
                draft = state.draft,
                onBack = leave,
                onStartTyping = vm::startTyping,
                onDraftChange = vm::updateDraft,
                onSend = { vm.send(contact) },
            )
        }
    }
}

@Composable
private fun NavBackStackEntry.sidemessageViewModel(nav: NavHostController): SidemessageViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    return viewModel(parent)
}
