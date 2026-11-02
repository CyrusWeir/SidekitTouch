package hrln.interactive.sidekit.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
fun LauncherRoute(onOpenApp: (SubApp) -> Unit, viewModel: LauncherViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LauncherScreen(
        state = state,
        onOpenApp = onOpenApp,
        onToggleLayout = viewModel::toggleLayout,
        onControlCentreOpenChange = viewModel::setControlCentreOpen,
        onToggle = viewModel::setToggle,
        onVolumeChange = viewModel::setVolume,
        onBrightnessChange = viewModel::setBrightness,
    )
}

/**
 * The SideKit home screen. Header = clock (tap or swipe down for the control centre,
 * long-press to switch grid/list), body = all apps, bottom = the blue dock.
 */
@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onOpenApp: (SubApp) -> Unit,
    onToggleLayout: () -> Unit,
    onControlCentreOpenChange: (Boolean) -> Unit,
    onToggle: (QuickToggle, Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
) {
    // Home never exits on back; back only closes the control centre.
    BackHandler { onControlCentreOpenChange(false) }

    Box(Modifier.fillMaxSize()) {
        SidekitScaffold(
            title = state.time,
            titleModifier = clockGestures(
                onOpenControlCentre = { onControlCentreOpenChange(true) },
                onToggleLayout = onToggleLayout,
            ),
            // The dock is the launcher's action bar, so opening an app morphs the tapped dock button
            // into the app's first action. The control centre panel takes its place (53:642).
            actions = if (state.controlCentreOpen) emptyList() else dockActions(onOpenApp),
            // Keypad Options key: the long-press gestures, as buttons.
            options = listOf(
                SideAction("layout", if (state.layout == LauncherLayout.Grid) "List" else "Grid", contentDescription = "Switch grid or list", onClick = onToggleLayout),
                SideAction("controls", "Controls", contentDescription = "Open control centre") { onControlCentreOpenChange(true) },
                SideAction("settings", icon = SubApp.Settings.icon, contentDescription = "Settings") { onOpenApp(SubApp.Settings) },
            ),
        ) { padding ->
            when (state.layout) {
                LauncherLayout.Grid -> AppGrid(SubApp.entries, padding, onOpenApp)
                LauncherLayout.List -> AppList(SubApp.listOrder, padding, onOpenApp)
            }
        }
        if (state.controlCentreOpen) {
            ControlCentre(
                state = state,
                onDismiss = { onControlCentreOpenChange(false) },
                onToggle = onToggle,
                onVolumeChange = onVolumeChange,
                onBrightnessChange = onBrightnessChange,
                modifier = Modifier.padding(top = SidekitTheme.spacing.pagePadding + SidekitTheme.spacing.headerHeight),
            )
        }
    }
}

/** The clock title: tap or pull down for the control centre, long-press to switch grid/list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun clockGestures(onOpenControlCentre: () -> Unit, onToggleLayout: () -> Unit): Modifier {
    val open by rememberUpdatedState(onOpenControlCentre)
    val toggle by rememberUpdatedState(onToggleLayout)
    val interaction = remember { MutableInteractionSource() }
    return remember {
        Modifier
            .testTag(LauncherTags.CLOCK)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Open control centre",
                onLongClickLabel = "Switch grid or list",
                onLongClick = { toggle() },
                onClick = { open() },
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 8f) open() }
            }
    }
}

@Composable
private fun AppGrid(apps: List<SubApp>, padding: PaddingValues, onOpenApp: (SubApp) -> Unit) {
    val listState = rememberLazyListState()
    val rows = remember(apps) { apps.chunked(GRID_COLUMNS) }
    SidekitList(padding, Modifier.testTag(LauncherTags.GRID), state = listState) {
        itemsIndexed(rows) { index, row ->
            SidekitItemRow(Modifier.listFade(listState, index)) {
                row.forEach { app -> AppTile(app, onOpenApp, Modifier.weight(1f)) }
                repeat(GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppList(apps: List<SubApp>, padding: PaddingValues, onOpenApp: (SubApp) -> Unit) {
    val listState = rememberLazyListState()
    SidekitList(padding, Modifier.testTag(LauncherTags.LIST), state = listState) {
        itemsIndexed(apps) { index, app ->
            SidekitItem(title = app.label, modifier = Modifier.listFade(listState, index), onClick = { onOpenApp(app) })
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
    const val CONTROL_CENTRE = "control_centre"
}

@SidephonePreview
@Composable
private fun LauncherGridPreview() = SidekitPreviewFrame {
    LauncherScreen(LauncherUiState(), {}, {}, {}, { _, _ -> }, {}, {})
}

@SidephonePreview
@Composable
private fun LauncherListPreview() = SidekitPreviewFrame {
    LauncherScreen(LauncherUiState(layout = LauncherLayout.List), {}, {}, {}, { _, _ -> }, {}, {})
}

@SidephonePreview
@Composable
private fun ControlCentrePreview() = SidekitPreviewFrame {
    LauncherScreen(LauncherUiState(layout = LauncherLayout.List, controlCentreOpen = true), {}, {}, {}, { _, _ -> }, {}, {})
}
