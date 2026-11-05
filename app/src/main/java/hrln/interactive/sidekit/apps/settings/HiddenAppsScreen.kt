package hrln.interactive.sidekit.apps.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp

internal fun SubApp.visibilityId() = "builtin:$name"
internal fun PortedApp.visibilityId() = "ported:$packageName"

@Composable
internal fun rememberHiddenApps(prefs: SidekitPreferences): Set<String> {
    var hidden by remember(prefs) { mutableStateOf(prefs.hiddenApps) }
    DisposableEffect(prefs) {
        val stop = prefs.observeHiddenApps { hidden = prefs.hiddenApps }
        hidden = prefs.hiddenApps
        onDispose { stop() }
    }
    return hidden
}

@Composable
internal fun HiddenAppsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { SidekitPreferences(context) }
    val hidden = rememberHiddenApps(prefs)
    val ported by remember { PortedApps.get(context).apps }.collectAsStateWithLifecycle()
    SidekitScaffold("Hide apps", actions = listOf(SideAction.back("back", "Settings", back))) { padding ->
        SidekitList(padding) { hiddenAppChoices(hidden, ported) { prefs.hiddenApps = it } }
    }
}

internal fun LazyListScope.hiddenAppChoices(hidden: Set<String>, ported: List<PortedApp>, save: (Set<String>) -> Unit) {
    item { SidekitText("Hide apps from home, search and the dock. Tap again to restore. Apps stay installed; Settings remains in Options.", style = SidekitTheme.typography.label.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = Int.MAX_VALUE) }
    item { SidekitLineButton("Show all apps", { save(emptySet()) }, modifier = Modifier.height(64.dp), variant = LineButtonVariant.Secondary) }
    val choices = SubApp.entries.map { it.visibilityId() to it.label } + ported.map { it.visibilityId() to it.label }
    items(choices, key = { it.first }) { (id, label) ->
        SidekitItem(label, Modifier.height(76.dp), secondary = if (id in hidden) "Hidden" else "Shown", onClick = {
            save(if (id in hidden) hidden - id else hidden + id)
        })
    }
}
