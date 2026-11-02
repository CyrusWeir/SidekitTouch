package hrln.interactive.sidekit.apps.maps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.*

@Composable
fun WhileVisible(key: Any? = Unit, work: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(work)
    LaunchedEffect(owner, key) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { latest() } }
}

@Composable
fun PlaceSearch(onBack: () -> Unit, onSelect: (Place) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<Place>()) }
    var status by remember { mutableStateOf("Search towns and cities · Open-Meteo / GeoNames") }
    WhileVisible(submitted to attempt) {
        if (submitted.isNotBlank()) {
            status = "Searching…"
            try { results = searchPlaces(submitted); status = if (results.isEmpty()) "No places found" else "Select a place" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Search unavailable. Check your connection and try again." }
        }
    }
    val list = rememberLazyListState()
    SidekitScaffold("Find a place", actions = listOf(SideAction.back("back", "Back", onBack), SideAction("search", "Search") { submitted = query.trim(); attempt++; results = emptyList() })) { padding ->
        SidekitList(padding, state = list) {
            item { SidekitTile(Modifier.listFade(list, 0)) { SidekitTextField(query, { query = it }, Modifier.fillMaxWidth().padding(SidekitTheme.spacing.itemPadding), placeholder = "Town or city", singleLine = true, focusOnKeypad = true, autoFocus = true, onDone = { submitted = query.trim(); attempt++ }) } }
            item { OutdoorInfo(status, modifier = Modifier.listFade(list, 1)) }
            items(results.size) { i -> OutdoorInfo(results[i].name, modifier = Modifier.listFade(list, i + 2), onClick = { onSelect(results[i]) }) }
        }
    }
}

/** The caller puts the explanation in its body and this action in its shared bar. */
@Composable
fun locationAction(onFix: (Place) -> Unit, onStatus: (String) -> Unit): SideAction {
    val context = LocalContext.current
    var request by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) request++ else onStatus("Location denied. You can still search for a place.")
    }
    WhileVisible(request) {
        if (request > 0) {
            onStatus("Finding location…")
            val place = locate(context)
            if (place != null) { onFix(place); onStatus("Approximate location") }
            else onStatus("Location unavailable. Enable location or search for a place.")
        }
    }
    return SideAction("location", if (hasLocation(context)) "Locate" else "Allow") {
        if (hasLocation(context)) request++ else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

/** Multiline copy belongs inside a standard tile so explanations remain readable at 480px. */
@Composable
fun OutdoorInfo(title: String, modifier: Modifier = Modifier, secondary: String? = null, onClick: (() -> Unit)? = null) {
    SidekitTile(modifier.fillMaxWidth(), onClick = onClick, contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
        Column(Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding)) {
            SidekitText(title, style = SidekitTheme.typography.caption, maxLines = if (secondary == null) 3 else 2)
            secondary?.let { SidekitText(it, style = SidekitTheme.typography.small, color = SidekitTheme.colors.surfaceA6, maxLines = 2) }
        }
    }
}
