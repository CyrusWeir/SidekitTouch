package hrln.interactive.sidekit.apps.maps

import android.hardware.GeomagneticField
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import hrln.interactive.sidekit.apps.compass.HeadingSensor
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp

class MapsViewModel : ViewModel() {
    var current by mutableStateOf<Place?>(null)
    var destination by mutableStateOf<Place?>(null)
}
fun NavGraphBuilder.liveMapsGraph(nav: NavHostController) {
    val root = SubApp.Maps.route
    navigation(startDestination = "$root/home", route = root) {
        composable("$root/home") { entry ->
            val parent = remember(entry) { nav.getBackStackEntry(root) }
            val vm: MapsViewModel = viewModel(parent)
            LiveMaps(vm) { nav.navigate("$root/search") }
        }
        composable("$root/search") { entry ->
            val parent = remember(entry) { nav.getBackStackEntry(root) }
            val vm: MapsViewModel = viewModel(parent)
            PlaceSearch({ nav.popBackStack() }) { vm.destination = it; nav.popBackStack() }
        }
    }
}
@Composable
private fun LiveMaps(vm: MapsViewModel, onSearch: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("Allow approximate location to show where you are, or search for a place.") }
    var fallback by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(0) }
    var recenter by remember { mutableStateOf(false) }
    val location = locationAction({ vm.current = it; recenter = true }) { message = it }
    val list = rememberLazyListState()
    SidekitScaffold("Maps", actions = listOf(SideAction.back("search", "Search", onSearch), SideAction("go", "Go") {
        val to = vm.destination
        if (to == null) message = "Search and select a destination first"
        else if (!openDirections(context, to)) { fallback = true; message = "No navigation app. Straight-line guidance only; paths and obstacles are not considered." }
    }, location), options = listOf(SideAction("zoomIn", "Zoom +") { zoom = kotlin.math.abs(zoom) + 1 }, SideAction("zoomOut", "Zoom −") { zoom = -kotlin.math.abs(zoom) - 1 }, SideAction("center", "Centre") { recenter = !recenter })) { padding ->
        SidekitList(padding, state = list) {
            val center = if (recenter) vm.current ?: vm.destination else vm.destination ?: vm.current
            if (center != null) item { Box(Modifier.listFade(list, 0)) { RasterMap(center, vm.current, vm.destination, zoom) { if (!fallback) message = it } } }
            item { OutdoorInfo(vm.destination?.name ?: "Choose a destination", secondary = vm.current?.let { "You: %.4f, %.4f".format(it.latitude, it.longitude) } ?: "Your location is not available", modifier = Modifier.listFade(list, if (center == null) 0 else 1)) }
            if (fallback && vm.current != null && vm.destination != null) item { StraightLine(vm.current!!, vm.destination!!) }
            item { OutdoorInfo(message, modifier = Modifier.listFade(list, 2)) }
        }
    }
}
@Composable
private fun StraightLine(from: Place, to: Place) {
    val context = LocalContext.current
    val sensor = remember { HeadingSensor(context) }
    val result = remember(from, to) { FloatArray(3).also { Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, it) } }
    val declination = remember(from) { GeomagneticField(from.latitude.toFloat(), from.longitude.toFloat(), 0f, System.currentTimeMillis()).declination }
    var accuracy by remember { mutableIntStateOf(0) }
    WhileVisible(sensor) { sensor.accuracy.collect { accuracy = it } }
    val color = SidekitTheme.colors.primaryA1
    SidekitTile(Modifier.fillMaxWidth().height(230.dp)) {
        Column {
            SidekitText("${result[0].toInt()} m · ${((result[1] + 360) % 360).toInt()}° true", Modifier.padding(SidekitTheme.spacing.itemPadding))
            if (sensor.available) Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                rotate(result[1] - (sensor.angle.floatValue + declination)) {
                    drawLine(color, center + Offset(0f, 30.dp.toPx()), center - Offset(0f, 30.dp.toPx()), 5.dp.toPx())
                    drawLine(color, center - Offset(0f, 30.dp.toPx()), center + Offset(-15.dp.toPx(), -10.dp.toPx()), 5.dp.toPx())
                    drawLine(color, center - Offset(0f, 30.dp.toPx()), center + Offset(15.dp.toPx(), -10.dp.toPx()), 5.dp.toPx())
                }
            }
            SidekitText(if (!sensor.available) "No compass sensor" else if (accuracy <= 1) "Calibrate: move in a figure eight" else "Hold phone flat", style = SidekitTheme.typography.small)
        }
    }
}
