package hrln.interactive.sidekit.apps.tripplanning

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import hrln.interactive.sidekit.apps.maps.*
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class TripViewModel(val repository: TripRepository, private val planner: TripPlanner = LocalTripPlanner()) : ViewModel() {
    var from by mutableStateOf("")
    var to by mutableStateOf("")
    var time by mutableStateOf("")
    var notes by mutableStateOf("")
    var selected by mutableStateOf<Trip?>(null)
    var editingId: String? = null
    val message = MutableStateFlow("Local plans only · no transit times or live departures")
    var saving by mutableStateOf(false)
        private set
    fun edit(trip: Trip?) {
        editingId = trip?.id; from = trip?.from.orEmpty(); to = trip?.to.orEmpty(); time = trip?.time.orEmpty(); notes = trip?.notes.orEmpty()
    }
    fun save(onSaved: () -> Unit) {
        if (saving) return
        if (repository.store.state.value == null) { message.value = "Loading saved trips…"; return }
        saving = true
        val draft = TripDraft(from, to, time, notes)
        viewModelScope.launch {
            try {
                val result = planner.plan(draft).let { it.copy(id = editingId ?: it.id) }
                repository.save(result); selected = result; onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: IllegalArgumentException) { message.value = "Enter a start and destination" }
            finally { saving = false }
        }
    }
}
fun NavGraphBuilder.liveTripsGraph(nav: NavHostController) {
    val root = SubApp.TripPlanning.route
    navigation(startDestination = "$root/home", route = root) {
        listOf("home", "saved", "plan", "detail", "places", "search").forEach { page -> composable("$root/$page") { entry ->
            val context = LocalContext.current
            val parent = remember(entry) { nav.getBackStackEntry(root) }
            val vm: TripViewModel = viewModel(parent, factory = viewModelFactory { initializer { TripViewModel(TripRepository.get(context)) } })
            val data by vm.repository.store.state.collectAsStateWithLifecycle()
            val message by vm.message.collectAsStateWithLifecycle()
            val error by vm.repository.store.error.collectAsStateWithLifecycle()
            val back = { nav.popBackStack(); Unit }
            if (page == "search") {
                PlaceSearch(back) { vm.repository.savePlace(it); back() }
            } else {
                val list = rememberLazyListState()
                val group = rememberSwipeRevealGroup()
                var useAsStart by remember { mutableStateOf(false) }
                var undo by remember { mutableStateOf<(() -> Unit)?>(null) }
                LaunchedEffect(undo) { if (undo != null) { delay(5_000); undo = null } }
                fun plan() { vm.edit(null); nav.navigate("$root/plan") }
                val actions = when (page) {
                    "home" -> listOf(SideAction.back("saved", "Saved") { nav.navigate("$root/saved") }, SideAction.forward("plan", "Plan", ::plan))
                    "plan" -> listOf(SideAction.back("back", "Cancel", back), SideAction("save", if (vm.saving) "Saving…" else "Save") { vm.save(back) })
                    "places" -> listOf(SideAction.back("back", "Back", back), SideAction("add", "Add") { nav.navigate("$root/search") })
                    "detail" -> listOf(SideAction.back("back", "Back", back), SideAction("go", "Directions") {
                        val trip = vm.selected
                        if (trip != null) {
                            val origin = if (trip.from == "Current location") "" else "&origin=${Uri.encode(trip.from)}"
                            val uri = "https://www.google.com/maps/dir/?api=1$origin&destination=${Uri.encode(trip.to)}"
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                            catch (_: android.content.ActivityNotFoundException) { vm.message.value = "No directions app installed. Your trip is saved locally." }
                        }
                    })
                    else -> listOf(SideAction.back("back", "Back", back), SideAction.forward("plan", "Plan", ::plan))
                }.let { actions -> undo?.let { restore -> actions + SideAction("undo", "Undo") { restore(); undo = null } } ?: actions }
                val options = if (page == "places") listOf(SideAction("useAs", if (useAsStart) "Use as destination" else "Use as start") { useAsStart = !useAsStart }) else if (page == "detail") listOf(SideAction("edit", "Edit") { vm.edit(vm.selected); nav.navigate("$root/plan") }) else listOf(SideAction("places", "Places") { nav.navigate("$root/places") })
                SidekitScaffold(when (page) { "plan" -> "Plan a trip"; "places" -> "Saved places"; "saved" -> "Saved trips"; else -> "Trip Planning" }, actions = actions, options = options) { padding ->
                    SidekitList(padding, state = list) {
                        when (page) {
                            "plan" -> {
                                item { TripField("From", vm.from, { vm.from = it }, Modifier.listFade(list, 0), true) }
                                item { TripField("To", vm.to, { vm.to = it }, Modifier.listFade(list, 1)) }
                                item { TripField("Time (your plan)", vm.time, { vm.time = it }, Modifier.listFade(list, 2)) }
                                item { TripField("Notes", vm.notes, { vm.notes = it }, Modifier.listFade(list, 3)) }
                            }
                            "places" -> {
                                if (data?.places.isNullOrEmpty()) item { OutdoorInfo("No saved places", modifier = Modifier.listFade(list, 0)) }
                                items(data?.places?.size ?: 0) { i -> val p = data!!.places[i]
                                    SidekitSwipeRow(p.key, group, { vm.repository.deletePlace(p); undo = { vm.repository.savePlace(p) } }, Modifier.listFade(list, i)) {
                                        OutdoorInfo(p.name, secondary = if (useAsStart) "Use as start" else "Use as destination", onClick = {
                                            val inEditor = nav.previousBackStackEntry?.destination?.route == "$root/plan"
                                            if (!inEditor) vm.edit(null)
                                            if (useAsStart) vm.from = p.name else vm.to = p.name
                                            if (vm.from.isBlank()) vm.from = "Current location"
                                            back()
                                            if (!inEditor) nav.navigate("$root/plan")
                                        })
                                    }
                                }
                            }
                            "detail" -> vm.selected?.let { t ->
                                item { OutdoorInfo(t.from, secondary = "To ${t.to}", modifier = Modifier.listFade(list, 0)) }
                                item { OutdoorInfo(t.time.ifBlank { "No time set" }, secondary = t.notes.ifBlank { "No notes" }, modifier = Modifier.listFade(list, 1)) }
                            }
                            else -> {
                                val trips = data?.trips.orEmpty()
                                if (trips.isEmpty()) item { OutdoorInfo("No saved trips", secondary = "Plan a trip to get started", modifier = Modifier.listFade(list, 0)) }
                                items(trips.size, key = { trips[it].id }) { i -> val t = trips[i]
                                    SidekitSwipeRow(t.id, group, { vm.repository.delete(t); undo = { vm.repository.save(t) } }, Modifier.listFade(list, i)) {
                                        OutdoorInfo("${t.from} → ${t.to}", secondary = t.time.ifBlank { "No time set" }, onClick = { vm.selected = t; nav.navigate("$root/detail") })
                                    }
                                }
                            }
                        }
                        item { OutdoorInfo(error ?: message, modifier = Modifier.listFade(list, if (page == "plan") 4 else 1)) }
                    }
                }
            }
        } }
    }
}
@Composable
private fun TripField(label: String, value: String, change: (String) -> Unit, modifier: Modifier, main: Boolean = false) {
    SidekitTile(modifier.fillMaxWidth()) {
        SidekitTextField(value, change, Modifier.fillMaxWidth().padding(SidekitTheme.spacing.itemPadding), placeholder = label, singleLine = true, focusOnKeypad = main, autoFocus = main, onDone = {})
    }
}
