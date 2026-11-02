package hrln.interactive.sidekit.apps.sideweather

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Sideweather.route
private val HOME = "$ROOT/home"
private val HOURLY = "$ROOT/hourly"
private val FIVE_DAY = "$ROOT/five-day"
private val LOCATIONS = "$ROOT/locations"

/** Sideweather (section 94:321). Content is static placeholder data, so there's no ViewModel. */
fun NavGraphBuilder.sideweatherGraph(nav: NavHostController) {
    val data = SideweatherSampleData
    navigation(startDestination = HOME, route = ROOT) {
        composable(HOME) {
            SideweatherHomeScreen(
                current = data.current,
                summary = data.summary,
                onHourly = { nav.navigate(HOURLY) },
                onFiveDay = { nav.navigate(FIVE_DAY) },
            )
        }
        composable(HOURLY) {
            ForecastScreen("Hourly forecast", data.hourly, onBack = { nav.popBackStack() }, onLocations = { nav.navigate(LOCATIONS) })
        }
        composable(FIVE_DAY) {
            ForecastScreen("5-day forecast", data.fiveDay, onBack = { nav.popBackStack() }, onLocations = { nav.navigate(LOCATIONS) })
        }
        composable(LOCATIONS) {
            LocationsScreen(data.locations, data.locationHint, onBack = { nav.popBackStack() }, onAdd = {})
        }
    }
}
