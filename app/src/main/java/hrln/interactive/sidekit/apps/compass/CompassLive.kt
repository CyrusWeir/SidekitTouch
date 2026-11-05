package hrln.interactive.sidekit.apps.compass

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import hrln.interactive.sidekit.design.theme.SidekitTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.apps.maps.*
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private data class HeadingBookmark(val time: Long, val heading: Float, val place: Place?)
private object Bookmarks {
    private var store: OutdoorStore<List<HeadingBookmark>>? = null
    @Synchronized fun get(context: android.content.Context): OutdoorStore<List<HeadingBookmark>> = store ?: OutdoorStore(
        File(context.filesDir, "compass.json"), { j -> val a = j.getJSONArray("items"); List(a.length()) { val o = a.getJSONObject(it); HeadingBookmark(o.getLong("time"), o.getDouble("heading").toFloat(), o.optJSONObject("place")?.let(Place::parse)) } },
        { items: List<HeadingBookmark> -> JSONObject().put("items", JSONArray(items.map { JSONObject().put("time", it.time).put("heading", it.heading).put("place", it.place?.json()) })) },
        emptyList(), CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ).also { store = it }
}

@Composable
fun LiveCompass(onBookmarks: () -> Unit) {
    val context = LocalContext.current
    val sensor = remember { HeadingSensor(context) }
    val store = remember { Bookmarks.get(context) }
    val loaded by store.state.collectAsStateWithLifecycle()
    val error by store.error.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(if (sensor.available) "Hold flat · magnetic north" else "No compass sensor on this device") }
    var lowAccuracy by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf<Place?>(null) }
    val location = locationAction({ place = it }) { status = it }
    WhileVisible(sensor) { sensor.accuracy.collect { lowAccuracy = it <= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW } }
    val list = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val bearingStyle = SidekitTheme.typography.title.copy(color = SidekitTheme.colors.light)
    SidekitScaffold("Compass", actions = listOf(SideAction("calibrate", "Calibrate") { status = "Move phone in a figure eight, away from metal; then hold flat" }, SideAction("save", "Save") {
        val heading = sensor.latest
        if (heading == null) status = "Waiting for a heading"
        else if (loaded == null) status = "Loading bookmarks…"
        else { store.edit { (listOf(HeadingBookmark(System.currentTimeMillis(), heading, place)) + it).take(100) }; status = "Heading saved${if (place == null) " without location" else " with location"}" }
    }), options = listOf(location, SideAction("bookmarks", "Bookmarks", onClick = onBookmarks))) { padding ->
        SidekitList(padding, state = list) {
            item { Box(Modifier.fillMaxWidth().height(278.dp).listFade(list, 0), contentAlignment = androidx.compose.ui.Alignment.TopCenter) { CompassDial({ sensor.angle.floatValue }, Modifier.padding(top = 5.dp)) } }
            item { SidekitTile(Modifier.fillMaxWidth().listFade(list, 1)) {
                Canvas(Modifier.fillMaxSize()) {
                    val text = if (sensor.latest == null) "Waiting…" else "${sensor.angle.floatValue.toInt()}° magnetic"
                    val layout = measurer.measure(text, bearingStyle)
                    drawText(layout, topLeft = Offset(30.dp.toPx(), (size.height - layout.size.height) / 2))
                }
            } }
            item { OutdoorInfo(error ?: if (lowAccuracy) "Low accuracy: move phone in a figure eight, away from metal" else status, modifier = Modifier.listFade(list, 2)) }
            item { OutdoorInfo("Location is optional", secondary = "Options → Allow attaches an approximate location to saved headings", modifier = Modifier.listFade(list, 3)) }
        }
    }
}

@Composable
fun CompassBookmarks(onBack: () -> Unit) {
    val store = Bookmarks.get(LocalContext.current)
    val rows by store.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val group = rememberSwipeRevealGroup()
    var deleted by remember { mutableStateOf<HeadingBookmark?>(null) }
    LaunchedEffect(deleted) { if (deleted != null) { delay(5_000); deleted = null } }
    SidekitScaffold("Saved headings", actions = listOfNotNull(SideAction.back("back", "Back", onBack), deleted?.let { p -> SideAction("undo", "Undo", tone = ActionTone.Muted) { store.edit { (it + p).sortedByDescending { b -> b.time } }; deleted = null } })) { padding ->
        SidekitList(padding, state = list) {
            if (rows.isNullOrEmpty()) item { OutdoorInfo("No saved headings", modifier = Modifier.listFade(list, 0)) }
            items(rows.orEmpty().size) { i -> val b = rows!![i]
                SidekitSwipeRow(b.time, group, { store.edit { it - b }; deleted = b }, Modifier.listFade(list, i)) {
                    OutdoorInfo("${b.heading.toInt()}° magnetic", secondary = b.place?.let { "${it.latitude}, ${it.longitude}" } ?: "No location")
                }
            }
        }
    }
}
