package hrln.interactive.sidekit.apps.phonebook

import android.Manifest
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import hrln.interactive.sidekit.apps.sidemessage.SidemessageRoutes
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Phonebook.route
private val HOME = "$ROOT/home"
private val RECENTS = "$ROOT/recents"
private const val ARG_ID = "id"
private const val ARG_NUMBER = "number"
private val CONTACT = "$ROOT/contact/{$ARG_ID}"
private val NUMBER = "$ROOT/number?$ARG_NUMBER={$ARG_NUMBER}"
private val CALL = "$ROOT/call?$ARG_NUMBER={$ARG_NUMBER}"
private val DIAL = "$ROOT/dial?$ARG_NUMBER={$ARG_NUMBER}"

object PhonebookRoutes {
    fun contact(id: Long) = "$ROOT/contact/$id"
    fun number(number: String) = "$ROOT/number?$ARG_NUMBER=${Uri.encode(number)}"
    fun dial(number: String = "") = "$ROOT/dial?$ARG_NUMBER=${Uri.encode(number)}"
}

fun NavGraphBuilder.phonebookGraph(nav: NavHostController) {
    navigation(startDestination = HOME, route = ROOT) {
        composable(HOME) { entry ->
            val vm = entry.phonebookViewModel(nav)
            val gate = rememberPermissionGate(Manifest.permission.READ_CONTACTS)
            LaunchedEffect(gate.granted) { vm.setContactsAccess(gate.granted) }
            val state by vm.contactsState.collectAsStateWithLifecycle()
            val call = caller(nav, entry)
            PhonebookHomeScreen(
                state = state,
                region = rememberRegion(),
                onAllow = gate::request,
                onDigit = vm::typeDigit,
                onDelete = vm::deleteDigit,
                onClear = vm::clearQuery,
                onOpen = { nav.navigateFrom(entry, PhonebookRoutes.contact(it.id)) },
                onRecents = { nav.navigateFrom(entry, RECENTS) },
                onDialPad = { nav.navigateFrom(entry, PhonebookRoutes.dial(state.query)) },
                onCall = call,
            )
        }
        composable(CONTACT, arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType })) { entry ->
            val vm = entry.phonebookViewModel(nav)
            val id = entry.arguments?.getLong(ARG_ID) ?: -1L
            val gate = rememberPermissionGate(Manifest.permission.READ_CONTACTS)
            LaunchedEffect(gate.granted) { vm.setContactsAccess(gate.granted) }
            val contacts by vm.contacts.collectAsStateWithLifecycle()
            val contact = remember(contacts, id) { contacts?.firstOrNull { it.id == id } }
            var selected by rememberSaveable { mutableIntStateOf(-1) }
            val numbers = contact?.numbers.orEmpty()
            val chosen = if (selected in numbers.indices) selected else numbers.indexOf(contact?.primaryNumber)
            DetailRoute(nav, entry, contact?.name ?: "", numbers, chosen) { selected = it }
        }
        composable(NUMBER, arguments = listOf(navArgument(ARG_NUMBER) { type = NavType.StringType; defaultValue = "" })) { entry ->
            val number = entry.arguments?.getString(ARG_NUMBER).orEmpty()
            val region = rememberRegion()
            DetailRoute(nav, entry, PhoneFormat.format(number, region), listOf(PhoneNumber(number, "Phone")), 0) {}
        }
        composable(RECENTS) { entry ->
            val vm = entry.phonebookViewModel(nav)
            val calls = rememberPermissionGate(Manifest.permission.READ_CALL_LOG)
            val contacts = rememberPermissionGate(Manifest.permission.READ_CONTACTS)
            LaunchedEffect(calls.granted, contacts.granted) {
                vm.setCallLogAccess(calls.granted)
                vm.setContactsAccess(contacts.granted)
            }
            val state by vm.recentsState.collectAsStateWithLifecycle()
            RecentsScreen(
                state = state,
                region = rememberRegion(),
                onAllow = calls::request,
                onOpen = { row ->
                    val route = row.contactId?.let(PhonebookRoutes::contact) ?: PhonebookRoutes.number(row.call.number)
                    if (row.call.number.isNotBlank()) nav.navigateFrom(entry, route)
                },
                onBack = { nav.popFrom(entry) },
                onDialPad = { nav.navigateFrom(entry, PhonebookRoutes.dial()) },
            )
        }
        composable(CALL, arguments = listOf(navArgument(ARG_NUMBER) { type = NavType.StringType; defaultValue = "" })) { entry ->
            val context = LocalContext.current
            val number = entry.arguments?.getString(ARG_NUMBER).orEmpty()
            val gate = rememberPermissionGate(Manifest.permission.CALL_PHONE) { ok ->
                if (ok) { placeCall(context, number); nav.popFrom(entry) }
            }
            CallPermissionScreen(number, onBack = { nav.popFrom(entry) }, onAllow = {
                if (gate.granted) { placeCall(context, number); nav.popFrom(entry) } else gate.request()
            }, onDialer = { openDialer(context, number) })
        }
        composable(DIAL, arguments = listOf(navArgument(ARG_NUMBER) { type = NavType.StringType; defaultValue = "" })) { entry ->
            val vm = entry.phonebookViewModel(nav)
            val prefill = entry.arguments?.getString(ARG_NUMBER).orEmpty()
            // Prefill once per visit; coming back to the pad (e.g. from Messages) keeps what was typed.
            var prefilled by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) { if (!prefilled) { vm.setDial(prefill); prefilled = true } }
            val number by vm.dial.collectAsStateWithLifecycle()
            val call = caller(nav, entry)
            DialPadScreen(
                number = number,
                region = rememberRegion(),
                onKey = vm::dialKey,
                onDelete = vm::dialDelete,
                onPlus = vm::dialPlus,
                onBack = { nav.popFrom(entry) },
                onCall = call,
                onMessage = { nav.navigateFrom(entry, SidemessageRoutes.conversation(listOf(it))) },
            )
        }
    }
}

@Composable
private fun DetailRoute(nav: NavHostController, entry: NavBackStackEntry, title: String, numbers: List<PhoneNumber>, selected: Int, onSelect: (Int) -> Unit) {
    val call = caller(nav, entry)
    ContactDetailScreen(
        title = title,
        numbers = numbers,
        selected = selected,
        region = rememberRegion(),
        onSelect = onSelect,
        onBack = { nav.popFrom(entry) },
        onCall = call,
        onMessage = { nav.navigateFrom(entry, SidemessageRoutes.conversation(listOf(it))) },
    )
}

@Composable
private fun rememberRegion(): String {
    val context = LocalContext.current
    return remember { PhoneFormat.region(context) }
}

/** Ignores repeat taps that land while a transition is already under way. */
private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id == entry.id) navigate(route) { launchSingleTop = true }
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** One ViewModel per visit to Phonebook, shared by its screens. */
@Composable
private fun NavBackStackEntry.phonebookViewModel(nav: NavHostController): PhonebookViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(parent, factory = remember(context) { viewModelFactory { initializer { PhonebookViewModel(Phonebook.source(context)) } } })
}

@Composable
private fun caller(nav: NavHostController, entry: NavBackStackEntry): (String) -> Unit {
    val context = LocalContext.current
    return { number ->
        if (number.isNotBlank()) {
            if (context.hasPermissions(Manifest.permission.CALL_PHONE)) placeCall(context, number)
            else nav.navigateFrom(entry, "$ROOT/call?$ARG_NUMBER=${Uri.encode(number)}")
        }
    }
}
