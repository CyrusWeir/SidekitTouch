package hrln.interactive.sidekit.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.apps.sideport.ui.PortedTile
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

const val GRID_COLUMNS = 4

@Composable
fun LauncherRoute(
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit = {},
    viewModel: LauncherViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ported by remember { PortedApps.get(context).apps }.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.loadPreferences(context)
        onPauseOrDispose {}
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LauncherScreen(
        state = state,
        onOpenApp = onOpenApp,
        onToggleLayout = viewModel::toggleLayout,
        ported = ported,
        onOpenPorted = onOpenPorted,
    )
}

/**
 * The SideKit home screen. Header = clock (long-press to switch grid/list), body = all apps,
 * bottom = the blue dock. With a D-pad, focus starts on the first app: the clock isn't a stop.
 */
@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onOpenApp: (SubApp) -> Unit,
    onToggleLayout: () -> Unit,
    ported: List<PortedApp> = emptyList(),
    onOpenPorted: (PortedApp) -> Unit = {},
) {
    // Home never exits on back.
    BackHandler {}

    SidekitScaffold(
        title = state.time,
        titleModifier = clockGestures(onToggleLayout),
        // The dock is the launcher's action bar, so opening an app morphs the tapped dock button
        // into the app's first action.
        actions = dockActions(onOpenApp),
        // Keypad Options key: the long-press gesture, as a button.
        options = listOf(
            SideAction("layout", if (state.layout == LauncherLayout.Grid) "List" else "Grid", contentDescription = "Switch grid or list", onClick = onToggleLayout),
            SideAction("settings", icon = SubApp.Settings.icon, contentDescription = "Settings") { onOpenApp(SubApp.Settings) },
        ),
    ) { padding ->
        when (state.layout) {
            LauncherLayout.Grid -> AppGrid(SubApp.entries, ported, padding, onOpenApp, onOpenPorted)
            LauncherLayout.List -> AppList(SubApp.listOrder, ported, padding, onOpenApp, onOpenPorted)
        }
    }
}

/**
 * The clock title: long-press to switch grid/list. A touch gesture only, not a clickable, so it
 * takes no D-pad focus (the Options key has the same switch).
 */
@Composable
private fun clockGestures(onToggleLayout: () -> Unit): Modifier {
    val toggle by rememberUpdatedState(onToggleLayout)
    return remember {
        Modifier
            .testTag(LauncherTags.CLOCK)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { toggle() }) }
    }
}

/** Sidekit's own apps, then the Android apps added through Sideport. */
@Composable
private fun AppGrid(
    apps: List<SubApp>,
    ported: List<PortedApp>,
    padding: PaddingValues,
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit,
) {
    val listState = rememberLazyListState()
    val rows = remember(apps, ported) { (apps + ported).chunked(GRID_COLUMNS) }
    SidekitList(padding, Modifier.testTag(LauncherTags.GRID), state = listState) {
        itemsIndexed(rows) { index, row ->
            SidekitItemRow(Modifier.listFade(listState, index)) {
                row.forEach { entry ->
                    when (entry) {
                        is SubApp -> AppTile(entry, onOpenApp, Modifier.weight(1f))
                        is PortedApp -> PortedTile(entry, onOpenPorted, Modifier.weight(1f))
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppList(
    apps: List<SubApp>,
    ported: List<PortedApp>,
    padding: PaddingValues,
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit,
) {
    val listState = rememberLazyListState()
    SidekitList(padding, Modifier.testTag(LauncherTags.LIST), state = listState) {
        itemsIndexed(apps) { index, app ->
            SidekitItem(title = app.label, modifier = Modifier.listFade(listState, index), onClick = { onOpenApp(app) })
        }
        itemsIndexed(ported, key = { _, app -> "ported.${app.packageName}" }) { index, app ->
            SidekitItem(title = app.label, modifier = Modifier.listFade(listState, apps.size + index), onClick = { onOpenPorted(app) })
        }
    }
}

@Composable
private fun AppTile(app: SubApp, onOpenApp: (SubApp) -> Unit, modifier: Modifier) {
    SidekitIconItem(
        icon = app.icon,
        contentDescription = app.label,
        modifier = modifier,
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = { onOpenApp(app) },
    )
}

/** The blue dock (53:1256), as action-bar buttons. */
private fun dockActions(onOpenApp: (SubApp) -> Unit): List<SideAction> = SubApp.dock.map { app ->
    SideAction("dock.${app.name}", icon = app.icon, contentDescription = app.label) { onOpenApp(app) }
}

object LauncherTags {
    const val CLOCK = "launcher_clock"
    const val GRID = "launcher_grid"
    const val LIST = "launcher_list"
}

@SidephonePreview
@Composable
private fun LauncherGridPreview() = SidekitPreviewFrame {
    LauncherScreen(LauncherUiState(), {}, {})
}

@SidephonePreview
@Composable
private fun LauncherListPreview() = SidekitPreviewFrame {
    LauncherScreen(LauncherUiState(layout = LauncherLayout.List), {}, {})
}
