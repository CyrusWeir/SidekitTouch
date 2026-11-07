package hrln.interactive.sidekit.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import hrln.interactive.sidekit.R
import hrln.interactive.sidekit.apps.settings.*
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.icons.SidekitIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.apps.sideport.ui.PortedAppIcon
import hrln.interactive.sidekit.apps.sideport.ui.PortedTile
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.home.HomePreferences
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.DevBuildPill
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

const val GRID_COLUMNS = 4

/**
 * A launcher entry resolved from the home order: either a built-in Sidekit app or a ported app.
 * Kept as one ordered list so both the grid and the list render from the same `HomePreferences`
 * order (Phase 0 decision) and ported apps can sit anywhere the user moved them.
 */
sealed interface LauncherEntry {
    val id: String
    val label: String
}

/** A built-in Sidekit app entry. */
class BuiltinEntry(val app: SubApp) : LauncherEntry {
    override val id: String get() = app.visibilityId()
    override val label: String get() = app.label
}

/** A ported (Sideport) app entry. */
class PortedEntry(val app: PortedApp) : LauncherEntry {
    override val id: String get() = app.visibilityId()
    override val label: String get() = app.label
}

/**
 * Maps ordered ids to renderable entries against the known builtin and ported maps, dropping
 * any id that is not visible to the launcher (unknown or uninstalled).
 */
internal fun toLauncherEntries(
    ids: List<String>,
    subsById: Map<String, SubApp>,
    portedById: Map<String, PortedApp>,
): List<LauncherEntry> = ids.mapNotNull { id ->
    subsById[id]?.let { BuiltinEntry(it) } ?: portedById[id]?.let { PortedEntry(it) }
}

@Composable
fun LauncherRoute(
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit = {},
    viewModel: LauncherViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { SidekitPreferences(context) }
    val hidden = rememberHiddenApps(prefs)
    val homePrefs = remember(context) { HomePreferences(context) }
    var homeRevision by remember { mutableStateOf(0) }
    DisposableEffect(homePrefs) {
        val unregister = homePrefs.observe { homeRevision++ }
        onDispose { unregister() }
    }
    val ported by remember { PortedApps.get(context).apps }.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.loadPreferences(context)
        onPauseOrDispose {}
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Resolve the configured home order and dock into renderable entries.
    val subsById = remember(SubApp.entries) { SubApp.entries.associateBy { it.visibilityId() } }
    val portedById = remember(ported) { ported.associateBy { it.visibilityId() } }
    val known = remember(subsById, portedById) { subsById.keys + portedById.keys }
    val homeEntries = remember(homeRevision, known, subsById, portedById) {
        toLauncherEntries(homePrefs.homeOrderFor(known), subsById, portedById)
    }
    // The dock is the first four *shown* apps, as Arrange apps labels them: a hidden app gives
    // up its dock slot to the next app instead of leaving the dock one short.
    val dockEntries = remember(homeRevision, known, hidden, subsById, portedById) {
        toLauncherEntries(homePrefs.dockFor(known - hidden), subsById, portedById)
    }
    LauncherScreen(
        state = state,
        hiddenApps = hidden,
        onOpenApp = onOpenApp,
        onToggleLayout = viewModel::toggleLayout,
        home = homeEntries,
        dock = dockEntries,
        onOpenPorted = onOpenPorted,
    )
}

/**
 * The SideKit home screen. Header = clock (long-press to switch grid/list), body = all apps,
 * bottom = the blue dock. With a D-pad, focus starts on the first app: the clock isn't a stop.
 *
 * Search is the one focus stop above the grid: Up on the first row moves into the query field.
 * Typing any letter also starts it; the mini QWERTY cross stays letters here (see [NavControls]).
 */
@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onOpenApp: (SubApp) -> Unit,
    onToggleLayout: () -> Unit,
    home: List<LauncherEntry> = emptyList(),
    onOpenPorted: (PortedApp) -> Unit = {},
    hiddenApps: Set<String> = emptySet(),
    dock: List<LauncherEntry> = emptyList(),
) {
    val query = rememberTextFieldState()
    val searchFocus = remember { FocusRequester() }
    val keypad = LocalKeypad.current
    val chrome = LocalChrome.current
    val focusManager = LocalFocusManager.current
    var searching by remember { mutableStateOf(false) }
    // Row (grid) or item (list) that holds navigation focus, or -1 when nothing does. Up only steps
    // into the query field from the first row; anywhere lower it is ordinary focus movement.
    var focusedRow by remember { mutableStateOf(-1) }
    val text = query.text.toString().trim()
    fun clearSearch() {
        chrome?.closeOptions()
        keypad?.finishTyping()
        query.clearText()
        focusManager.clearFocus()
        focusedRow = -1
        searching = false
    }
    fun startSearch() {
        searching = true
    }
    // The field is only focusable while searching (`canFocus` below), so focus it once the frame
    // that made it focusable has been composed, not from the key event that started the search.
    LaunchedEffect(searching) { if (searching) runCatching { searchFocus.requestFocus() } }
    val clear = SideAction.square("search.clear", SidekitIcon.Close, "Clear app search", ::clearSearch)
    val entries = home.ifEmpty { defaultHome() }
    val visible = entries.filter { it.id !in hiddenApps && it.label.contains(text, ignoreCase = true) }
    BackHandler { clearSearch() }

    SidekitScaffold(
        title = null,
        modifier = Modifier.onPreviewKeyEvent { event ->
            // Up on the first row steps into search, so the D-pad (or the QWERTY rocker) can reach it
            // without typing a letter first.
            if (event.type == KeyEventType.KeyDown && !searching && text.isEmpty() && focusedRow == 0 &&
                event.nativeKeyEvent.repeatCount == 0 && event.key == Key.DirectionUp
            ) {
                startSearch()
                true
            } else false
        },
        header = {
            // The dev build type is the only one with a `build_label`, so the pill (and the width
            // it costs the query field) appears there and nowhere else.
            val buildLabel = stringResource(R.string.build_label)
            val titleLine = with(LocalDensity.current) { SidekitTheme.typography.title.lineHeight.toDp() }
            Row(
                modifier = Modifier.fillMaxWidth().height(SidekitTheme.spacing.headerHeight).padding(horizontal = SidekitTheme.spacing.itemPadding).padding(top = SidekitTheme.spacing.itemPadding),
                horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) {
                    SidekitTextField(
                        state = query,
                        focusRequester = searchFocus,
                        modifier = Modifier.fillMaxWidth().testTag(LauncherTags.SEARCH).focusProperties { canFocus = searching },
                        textStyle = SidekitTheme.typography.title,
                        placeholder = if (searching) "Search apps" else null,
                        focusOnKeypad = true,
                        onKeypadFocus = { searching = true },
                        singleLine = true,
                        trailingTypingAction = clear,
                        onDone = { focusManager.clearFocus() },
                    )
                    if (!searching && text.isEmpty()) {
                        Box(Modifier.fillMaxSize().then(clockGestures(onToggleLayout) { searching = true; searchFocus.requestFocus() })) {
                            SidekitText(state.time, style = SidekitTheme.typography.title)
                        }
                    }
                }
                if (buildLabel.isNotEmpty()) {
                    // Sits on the title's line box rather than the header's centre, so it stays on the
                    // clock's baseline whatever the header height is.
                    Box(Modifier.height(titleLine), contentAlignment = Alignment.Center) { DevBuildPill(buildLabel) }
                }
            }
        },
        // The dock is the launcher's action bar, so opening an app morphs the tapped dock button
        // into the app's first action.
        actions = if (searching || text.isNotEmpty()) listOf(
            SideAction("search.query", text.ifEmpty { "Search apps" }, dense = true, onClick = { searchFocus.requestFocus() }),
        ) else dockActions(dock.ifEmpty { defaultDock() }, hiddenApps, onOpenApp, onOpenPorted),
        trailingAction = if (searching || text.isNotEmpty()) clear else null,
        // Keypad Options key: the long-press gesture, as a button.
        options = listOf(
            SideAction("layout", if (state.layout == LauncherLayout.Grid) "List" else "Grid", contentDescription = "Switch grid or list", onClick = onToggleLayout),
            SideAction("settings", icon = SubApp.Settings.icon, contentDescription = "Settings") { onOpenApp(SubApp.Settings) },
        ),
    ) { padding ->
        if (visible.isEmpty()) {
            SidekitText(if (text.isNotEmpty()) "No matching apps" else "No apps shown. Use Options → Settings to show apps.", maxLines = Int.MAX_VALUE)
        } else when (state.layout) {
            LauncherLayout.Grid -> AppGrid(visible, padding, onOpenApp, onOpenPorted) { focusedRow = it }
            LauncherLayout.List -> AppList(visible, padding, onOpenApp, onOpenPorted) { focusedRow = it }
        }
    }
}

/**
 * The clock title: long-press to switch grid/list. A touch gesture only, not a clickable, so it
 * takes no D-pad focus (the Options key has the same switch).
 */
@Composable
private fun clockGestures(onToggleLayout: () -> Unit, onSearch: () -> Unit): Modifier {
    val toggle by rememberUpdatedState(onToggleLayout)
    val search by rememberUpdatedState(onSearch)
    return remember {
        Modifier
            .testTag(LauncherTags.CLOCK)
            .pointerInput(Unit) { detectTapGestures(onTap = { search() }, onLongPress = { toggle() }) }
    }
}

@Composable
private fun AppGrid(
    entries: List<LauncherEntry>,
    padding: PaddingValues,
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit,
    onFocusedRow: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val rows = remember(entries) { entries.chunked(GRID_COLUMNS) }
    SidekitList(padding, Modifier.testTag(LauncherTags.GRID), state = listState) {
        itemsIndexed(rows, key = { _, row -> row.joinToString { it.id } }) { index, row ->
            SidekitItemRow(Modifier.listFade(listState, index)) {
                row.forEach { entry ->
                    val focus = Modifier.weight(1f).onFocusChanged { onFocusedRow(if (it.isFocused) index else -1) }
                    when (entry) {
                        is BuiltinEntry -> AppTile(entry.app, onOpenApp, focus)
                        is PortedEntry -> PortedTile(entry.app, onOpenPorted, focus)
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppList(
    entries: List<LauncherEntry>,
    padding: PaddingValues,
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit,
    onFocusedRow: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    SidekitList(padding, Modifier.testTag(LauncherTags.LIST), state = listState) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            SidekitItem(
                title = entry.label,
                modifier = Modifier.listFade(listState, index).onFocusChanged { onFocusedRow(if (it.isFocused) index else -1) },
                onClick = {
                    when (entry) {
                        is BuiltinEntry -> onOpenApp(entry.app)
                        is PortedEntry -> onOpenPorted(entry.app)
                    }
                },
            )
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

/** The blue dock (53:1256), as action-bar buttons, in the configured home order. */
private fun dockActions(
    dock: List<LauncherEntry>,
    hidden: Set<String>,
    onOpenApp: (SubApp) -> Unit,
    onOpenPorted: (PortedApp) -> Unit,
): List<SideAction> = dock.filter { it.id !in hidden }.map { entry ->
    when (entry) {
        is BuiltinEntry -> SideAction("dock.${entry.app.name}", icon = entry.app.icon, contentDescription = entry.app.label) { onOpenApp(entry.app) }
        is PortedEntry -> SideAction(
            "dock.${entry.app.packageName}",
            label = entry.app.label,
            contentDescription = entry.app.label,
            artKey = entry.app.packageName,
            art = { PortedAppIcon(entry.app) },
        ) { onOpenPorted(entry.app) }
    }
}

/** Built-ins in declaration order, used when no configured `HomePreferences` order is supplied. */
private fun defaultHome(): List<LauncherEntry> = SubApp.entries.map { BuiltinEntry(it) }

/** The default dock (four built-ins), used when no configured dock is supplied. */
private fun defaultDock(): List<LauncherEntry> = SubApp.dock.map { BuiltinEntry(it) }

object LauncherTags {
    const val SEARCH = "launcher_search"
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