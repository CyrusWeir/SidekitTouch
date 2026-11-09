package hrln.interactive.sidekit.apps.sideweather

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.apps.maps.*
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** [city] looks up the town for a current-location fix; the default (tests) knows none. */
class WeatherViewModel(val repository: WeatherRepository, private val city: suspend (Place) -> String? = { null }) : ViewModel() {
    val forecast = MutableStateFlow<Forecast?>(null)
    val status = MutableStateFlow("Select a location")
    /**
     * Shows the fix straight away as "Current location", then adds its town once the geocoder
     * answers ("Current location · Madrid"). Offline, the plain name stays until it is next shown.
     */
    fun selectCurrentLocation(fix: Place) { repository.select(fix); nameCurrentLocation(fix) }
    private fun nameCurrentLocation(fix: Place) = viewModelScope.launch {
        city(fix)?.let { repository.rename(fix, currentLocationName(it)) }
    }
    suspend fun visible(place: Place?) {
        forecast.value = null
        if (place == null) return
        // Saved before towns were looked up, or while offline.
        if (place.name == CURRENT_LOCATION) nameCurrentLocation(place)
        forecast.value = repository.cached(place)
        while (currentCoroutineContext().isActive) {
            status.value = if (forecast.value == null) "Loading forecast…" else "Cached forecast"
            try { forecast.value = repository.refresh(place); status.value = "Open-Meteo · updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(forecast.value!!.received))}" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.value = if (forecast.value == null) "Forecast unavailable. Try again when online." else "Offline · showing saved forecast" }
            delay(30 * 60_000L)
        }
    }
}

fun NavGraphBuilder.sideweatherGraph(nav: NavHostController) {
    val root = SubApp.Sideweather.route
    navigation(startDestination = "$root/home", route = root) {
        listOf("home", "hourly", "five-day", "locations", "search").forEach { page ->
            composable("$root/$page") { entry ->
                val context = LocalContext.current
                val parent = remember(entry) { nav.getBackStackEntry(root) }
                val vm: WeatherViewModel = viewModel(parent, factory = viewModelFactory { initializer { WeatherViewModel(WeatherRepository.get(context)) { cityName(context.applicationContext, it) } } })
                val settings by vm.repository.settings.state.collectAsStateWithLifecycle()
                val forecast by vm.forecast.collectAsStateWithLifecycle()
                val status by vm.status.collectAsStateWithLifecycle()
                val error by vm.repository.settings.error.collectAsStateWithLifecycle()
                val place = settings?.places?.firstOrNull { it.key == settings?.selected }
                val imperial = settings?.fahrenheit == true
                if (page in listOf("home", "hourly", "five-day")) WhileVisible(place) { vm.visible(place) }
                val back = { nav.popBackStack(); Unit }
                val locations = { nav.navigate("$root/locations") { launchSingleTop = true }; Unit }
                val options = listOf(SideAction("locations", "Locations", onClick = locations), SideAction("units", if (imperial) "Use °C" else "Use °F") { vm.repository.settings.edit { it.copy(fahrenheit = !it.fahrenheit) } })
                when (page) {
                    "search" -> PlaceSearch(back) { vm.repository.select(it); nav.popBackStack() }
                    "locations" -> {
                        var message by remember { mutableStateOf("Allow approximate location for nearby weather, or add a town.") }
                        var deleted by remember { mutableStateOf<Place?>(null) }
                        LaunchedEffect(deleted) { if (deleted != null) { delay(5_000); deleted = null } }
                        val locate = locationAction(vm::selectCurrentLocation) { message = it }
                        val list = rememberLazyListState()
                        val group = rememberSwipeRevealGroup()
                        SidekitScaffold("Locations", actions = listOf(SideAction.back("back", "Back", back), deleted?.let { p -> SideAction("add", "Undo", tone = ActionTone.Muted) { vm.repository.select(p); deleted = null } } ?: SideAction("add", "Add") { nav.navigate("$root/search") }, locate)) { padding ->
                            SidekitList(padding, state = list) {
                                item { OutdoorInfo(error ?: message, modifier = Modifier.listFade(list, 0)) }
                                val places = settings?.places.orEmpty()
                                items(places.size, key = { places[it].key }) { i ->
                                    val p = places[i]
                                    SidekitSwipeRow(p.key, group, { vm.repository.delete(p); deleted = p }, Modifier.listFade(list, i + 1)) {
                                        OutdoorInfo(p.name, secondary = if (p.key == place?.key) "Selected" else null, onClick = { vm.repository.select(p); back() })
                                    }
                                }
                            }
                        }
                    }
                    "home" -> SideweatherHomeScreen(forecast?.let { f -> place?.let { f.current(it, imperial) } } ?: CurrentConditions(place?.name ?: "Choose a location", "—", "", ""), forecast?.summary(imperial).orEmpty() + WeatherRow(error ?: status, ""), { nav.navigate("$root/hourly") }, { nav.navigate("$root/five-day") }, options)
                    else -> ForecastScreen(if (page == "hourly") "Hourly forecast" else "5-day forecast", (if (page == "hourly") forecast?.hourly(imperial) else forecast?.daily(imperial)).orEmpty() + WeatherRow(status, ""), back, locations, place = place?.name)
                }
            }
        }
    }
}
