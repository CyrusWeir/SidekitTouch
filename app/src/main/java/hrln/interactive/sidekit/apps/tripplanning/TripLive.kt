package hrln.interactive.sidekit.apps.tripplanning

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
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class TripViewModel(val repository: TripRepository, private val planner: TripPlanner = LocalTripPlanner(), private val transit: NswTransit = NswTransit()) : ViewModel() {
    var from by mutableStateOf("")
    var to by mutableStateOf("")
    var time by mutableStateOf("")
    var notes by mutableStateOf("")
    var selected by mutableStateOf<Trip?>(null)
    var editingId: String? = null
    private var itinerary = ""
    val message = MutableStateFlow("NSW public transport · times shown in Sydney time")
    var stops by mutableStateOf<List<TransitStop>>(emptyList())
    var origin by mutableStateOf<TransitStop?>(null)
    var destination by mutableStateOf<TransitStop?>(null)
    var choosingOrigin by mutableStateOf(true)
    var journeys by mutableStateOf<List<TransitJourney>>(emptyList())
    var departures by mutableStateOf<List<TransitDeparture>>(emptyList())
    var busy by mutableStateOf(false)
    fun search(fromOrigin: Boolean, onReady: () -> Unit) {
        if (busy) return
        choosingOrigin = fromOrigin
        val query = if (fromOrigin) from else to
        request {
            stops = transit.stops(query)
            message.value = if (stops.isEmpty()) "No matching stops" else "Choose a stop"
            onReady()
        }
    }
    fun find(onReady: () -> Unit) = request {
        val a = origin?.takeIf { it.name == from } ?: throw IllegalArgumentException("Search and choose your From stop first")
        val b = destination?.takeIf { it.name == to } ?: throw IllegalArgumentException("Search and choose your To stop first")
        journeys = transit.journeys(a, b, time)
        message.value = if (journeys.isEmpty()) "No journeys found" else "Times are a snapshot · refresh before travelling"
        onReady()
    }
    fun board(onReady: () -> Unit) = request {
        val stop = origin?.takeIf { it.name == from } ?: throw IllegalArgumentException("Search and choose a From stop first")
        departures = transit.departures(stop)
        message.value = "Updated ${java.time.LocalTime.now(java.time.ZoneId.of("Australia/Sydney")).withNano(0)} · Sydney time"
        onReady()
    }
    private fun request(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Transit service unavailable" }
            finally { busy = false }
        }
    }
    fun saveJourney(journey: TransitJourney, onSaved: () -> Unit) {
        itinerary = journey.legs.joinToString("\n") { "${it.route}: ${it.from} → ${it.to} · ${transitTime(it.departure)}–${transitTime(it.arrival)} · ${if (it.live) "Live estimate" else "Scheduled"}" }
        time = "${journey.departure} – ${journey.arrival}"
        save(onSaved)
    }
    var saving by mutableStateOf(false)
        private set
    fun edit(trip: Trip?) {
        itinerary = trip?.itinerary.orEmpty()
        origin = trip?.origin; destination = trip?.destination; editingId = trip?.id; from = trip?.from.orEmpty(); to = trip?.to.orEmpty(); time = trip?.time.orEmpty().let { value -> runCatching { java.time.Instant.parse(value.substringBefore(" – ")).atZone(java.time.ZoneId.of("Australia/Sydney")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault(value) }; notes = trip?.notes.orEmpty()
    }
    fun save(onSaved: () -> Unit) {
        if (saving) return
        if (repository.store.state.value == null) { message.value = "Loading saved trips…"; return }
        saving = true
        val draft = TripDraft(from, to, time, notes)
        viewModelScope.launch {
            try {
                val result = planner.plan(draft).let { it.copy(id = editingId ?: it.id, origin = origin?.takeIf { s -> s.name == draft.from }, destination = destination?.takeIf { s -> s.name == draft.to }, itinerary = itinerary) }
                repository.save(result); selected = result; onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: IllegalArgumentException) { message.value = "Enter a start and destination" }
            finally { saving = false }
        }
    }
}
fun NavGraphBuilder.liveTripsGraph(nav: NavHostController, transit: NswTransit = NswTransit()) {
    val root = SubApp.TripPlanning.route
    navigation(startDestination = "$root/home", route = root) {
        listOf("home", "saved", "plan", "detail", "places", "search", "stops", "journeys", "departures").forEach { page -> composable("$root/$page") { entry ->
            val context = LocalContext.current
            val parent = remember(entry) { nav.getBackStackEntry(root) }
            val vm: TripViewModel = viewModel(parent, factory = viewModelFactory { initializer { TripViewModel(TripRepository.get(context), transit = transit) } })
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
                    "detail" -> listOf(SideAction.back("back", "Back", back), SideAction("replan", "Plan again") { vm.edit(vm.selected); nav.navigate("$root/plan") })
                    "departures" -> listOf(SideAction.back("back", "Back", back), SideAction("refresh", if (vm.busy) "Loading…" else "Refresh") { vm.board {} })
                    "stops", "journeys" -> listOf(SideAction.back("back", "Back", back))
                    else -> listOf(SideAction.back("back", "Back", back), SideAction.forward("plan", "Plan", ::plan))
                }.let { actions -> undo?.let { restore -> actions + SideAction("undo", "Undo", tone = ActionTone.Muted) { restore(); undo = null } } ?: actions }
                val options = if (page == "places") listOf(SideAction("useAs", if (useAsStart) "Use as destination" else "Use as start") { useAsStart = !useAsStart }) else if (page == "detail") listOf(SideAction("edit", "Edit") { vm.edit(vm.selected); nav.navigate("$root/plan") }) else if (page == "plan") listOf(
                    SideAction("fromSearch", "Find From stop") { vm.search(true) { nav.navigate("$root/stops") } },
                    SideAction("toSearch", "Find To stop") { vm.search(false) { nav.navigate("$root/stops") } },
                    SideAction("journeys", "Find journeys") { vm.find { nav.navigate("$root/journeys") } },
                    SideAction("departures", "Departures from here") { vm.board { nav.navigate("$root/departures") } },
                ) else listOf(SideAction("places", "Places") { nav.navigate("$root/places") })
                SidekitScaffold(when (page) { "plan" -> "Plan a trip"; "places" -> "Saved places"; "saved" -> "Saved trips"; else -> "Trip Planning" }, actions = actions, options = options) { padding ->
                    SidekitList(padding, state = list) {
                        when (page) {
                            "plan" -> {
                                item { TripField("From", vm.from, { vm.from = it }, Modifier.listFade(list, 0), true) }
                                item { TripField("To", vm.to, { vm.to = it }, Modifier.listFade(list, 1)) }
                                item { TripField("Sydney time: yyyy-MM-dd HH:mm (or now)", vm.time, { vm.time = it }, Modifier.listFade(list, 2)) }
                                item { TripField("Notes", vm.notes, { vm.notes = it }, Modifier.listFade(list, 3)) }
                                item { TripInfo("Find From stop", secondary = vm.origin?.name ?: "Search the start entered above", onClick = { vm.search(true) { nav.navigate("$root/stops") } }) }
                                item { TripInfo("Find To stop", secondary = vm.destination?.name ?: "Search the destination entered above", onClick = { vm.search(false) { nav.navigate("$root/stops") } }) }
                                item { TripInfo(if (vm.busy) "Loading transit…" else "Find journeys", secondary = "Public transport options from Transport for NSW", onClick = { vm.find { nav.navigate("$root/journeys") } }) }
                                item { TripInfo("Departures from here", secondary = "Next services from your selected From stop", onClick = { vm.board { nav.navigate("$root/departures") } }) }
                            }
                            "stops" -> items(vm.stops.size) { i -> val stop = vm.stops[i]
                                TripInfo(stop.name, secondary = stop.type, onClick = {
                                    if (vm.choosingOrigin) { vm.origin = stop; vm.from = stop.name } else { vm.destination = stop; vm.to = stop.name }; back()
                                })
                            }
                            "journeys" -> items(vm.journeys.size) { i -> val j = vm.journeys[i]
                                TripInfo("${transitTime(j.departure)}–${transitTime(j.arrival)} · ${j.legs.joinToString(" → ") { it.route }}",
                                    secondary = j.legs.joinToString("\n") { "${it.from} → ${it.to} · ${if (it.live) "Live estimate" else "Scheduled"}" } + "\nTap to save journey",
                                    onClick = { vm.saveJourney(j) { nav.navigate("$root/home") { popUpTo("$root/home") { inclusive = true } } } })
                            }
                            "departures" -> {
                                if (vm.departures.isEmpty()) item { TripInfo("No departures found") }
                                items(vm.departures.size) { i -> val d = vm.departures[i]
                                    TripInfo("${d.route} → ${d.destination}", secondary = "${transitTime(d.estimated ?: d.planned)} · ${if (d.cancelled) "Cancelled" else if (d.live) "Live estimate (scheduled ${transitTime(d.planned)})" else "Scheduled"}\n${d.platform}")
                                }
                            }
                            "places" -> {
                                if (data?.places.isNullOrEmpty()) item { TripInfo("No saved places", modifier = Modifier.listFade(list, 0)) }
                                items(data?.places?.size ?: 0) { i -> val p = data!!.places[i]
                                    SidekitSwipeRow(p.key, group, { vm.repository.deletePlace(p); undo = { vm.repository.savePlace(p) } }, Modifier.listFade(list, i)) {
                                        TripInfo(p.name, secondary = if (useAsStart) "Use as start" else "Use as destination", onClick = {
                                            val inEditor = nav.previousBackStackEntry?.destination?.route == "$root/plan"
                                            if (!inEditor) vm.edit(null)
                                            if (useAsStart) vm.from = p.name else vm.to = p.name

                                            back()
                                            if (!inEditor) nav.navigate("$root/plan")
                                        })
                                    }
                                }
                            }
                            "detail" -> vm.selected?.let { t ->
                                item { TripInfo(t.from, secondary = "To ${t.to}", modifier = Modifier.listFade(list, 0)) }
                                item { TripInfo(savedTripTime(t.time).ifBlank { "No time set" }, secondary = t.notes.ifBlank { "No notes" }, modifier = Modifier.listFade(list, 1)) }
                                if (t.itinerary.isNotBlank()) item { TripInfo("Saved itinerary · refresh before travelling", secondary = t.itinerary) }
                            }
                            else -> {
                                val trips = data?.trips.orEmpty()
                                if (trips.isEmpty()) item { TripInfo("No saved trips", secondary = "Plan a trip to get started", modifier = Modifier.listFade(list, 0)) }
                                items(trips.size, key = { trips[it].id }) { i -> val t = trips[i]
                                    SidekitSwipeRow(t.id, group, { vm.repository.delete(t); undo = { vm.repository.save(t) } }, Modifier.listFade(list, i)) {
                                        TripInfo("${t.from} → ${t.to}", secondary = savedTripTime(t.time).ifBlank { "No time set" }, onClick = { vm.selected = t; nav.navigate("$root/detail") })
                                    }
                                }
                            }
                        }
                        item { TripInfo(error ?: message, modifier = Modifier.listFade(list, if (page == "plan") 4 else 1)) }
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

@Composable
private fun TripInfo(title: String, modifier: Modifier = Modifier, secondary: String? = null, onClick: (() -> Unit)? = null) {
    SidekitCard(modifier.fillMaxWidth().heightIn(min = SidekitTheme.spacing.itemHeight), onClick = onClick) {
        Column {
            SidekitText(title, style = SidekitTheme.typography.caption, maxLines = Int.MAX_VALUE)
            secondary?.let { SidekitText(it, style = SidekitTheme.typography.small, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE) }
        }
    }
}
