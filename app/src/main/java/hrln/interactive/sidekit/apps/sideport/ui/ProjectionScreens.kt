package hrln.interactive.sidekit.apps.sideport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.apps.sideport.interpret.ActionSpec
import hrln.interactive.sidekit.apps.sideport.interpret.Block
import hrln.interactive.sidekit.apps.sideport.interpret.ButtonBlock
import hrln.interactive.sidekit.apps.sideport.interpret.FieldBlock
import hrln.interactive.sidekit.apps.sideport.interpret.ItemBlock
import hrln.interactive.sidekit.apps.sideport.interpret.ScreenModel
import hrln.interactive.sidekit.apps.sideport.interpret.TextBlock
import hrln.interactive.sidekit.apps.sideport.interpret.Tone
import hrln.interactive.sidekit.apps.sideport.model.UiNode
import hrln.interactive.sidekit.apps.sideport.service.ProjectionActions
import hrln.interactive.sidekit.apps.sideport.service.ProjectionState
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.LineButtonVariant
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitLineButton
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * The projection window's content: a full Sidekit canvas (title, body, action bar) drawn from the
 * ported app's screen. [screenHeightPx] keeps the canvas at full height when the window is
 * shortened above a soft keyboard, so the top is cut off rather than everything shrinking.
 */
@Composable
fun ProjectionOverlay(state: ProjectionState, actions: ProjectionActions, chrome: ChromeState, keypad: Keypad, screenHeightPx: Int) {
    val fullHeight = with(LocalDensity.current) { screenHeightPx.toDp() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                .height(fullHeight),
        ) {
            SidekitCanvas(chrome = chrome, keypad = keypad) {
                if (state.inspector) InspectorScreen(state, actions) else ProjectedScreen(state, actions)
            }
        }
    }
}

/** A ported app's screen in Sidekit form. */
@Composable
fun ProjectedScreen(state: ProjectionState, actions: ProjectionActions) {
    var menu by remember { mutableStateOf(false) }
    BackHandler(enabled = menu) { menu = false }
    val model = state.model
    when {
        model == null -> OpeningScreen(state, actions)
        menu -> MenuScreen(state, model, actions) { menu = false }
        else -> ScreenBody(state, model, actions) { menu = true }
    }
}

@Composable
private fun OpeningScreen(state: ProjectionState, actions: ProjectionActions) {
    SidekitScaffold(
        state.app.label,
        actions = listOf(SideAction.back("back", "Sidekit", actions::exitToSidekit)),
        options = listOf(SideAction("original", "Original view", onClick = actions::showOriginal)),
    ) { padding ->
        SidekitList(padding) {
            item { SidekitCard(Modifier.fillMaxWidth()) { SidekitText("Opening ${state.app.label}…", style = SidekitTheme.typography.label, maxLines = 3) } }
        }
    }
}

@Composable
private fun ScreenBody(state: ProjectionState, model: ScreenModel, actions: ProjectionActions, openMenu: () -> Unit) {
    val bar = remember(model.actions, actions) { actionBar(model.actions, actions) }
    val options = remember(model.options, actions) {
        listOf(
            SideAction("menu", "Menu", onClick = openMenu),
            SideAction("original", "Original", tone = ActionTone.Muted, onClick = actions::showOriginal),
            SideAction("sidekit", "Sidekit", tone = ActionTone.Muted, onClick = actions::exitToSidekit),
        )
    }
    val list = rememberLazyListState()
    SidekitScaffold(model.title ?: state.app.label, actions = bar, options = options) { padding ->
        SidekitList(padding, state = list) {
            var index = 0
            state.notice?.let { notice ->
                val i = index++
                item(key = "notice") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, i), onClick = { state.notice = null }) {
                        SidekitText(notice, style = SidekitTheme.typography.label, maxLines = 4)
                    }
                }
            }
            state.foreignPackage?.let { other ->
                val i = index++
                item(key = "foreign") {
                    SidekitText("From $other", Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding).listFade(list, i), style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
                }
            }
            index = blocks(model.header, "h", index, list, actions)
            if (model.list?.canScrollBackward == true && state.items.isNotEmpty()) {
                val i = index++
                item(key = "earlier") {
                    SidekitItem("Earlier", Modifier.listFade(list, i), colour = ItemColour.Muted, icon = SidekitIcon.ArrowUpward, onClick = { actions.loadMore(forward = false) })
                }
            }
            index = blocks(state.items, "i", index, list, actions)
            if (state.loadingMore) {
                val i = index++
                item(key = "loading") { SidekitItem("Loading…", Modifier.listFade(list, i), colour = ItemColour.Muted) }
            }
            index = blocks(model.footer, "f", index, list, actions)
            if (model.isEmpty && state.items.isEmpty()) {
                item(key = "empty") {
                    SidekitCard(Modifier.fillMaxWidth()) {
                        SidekitText(
                            "Nothing on this screen can be read. Press Options and choose Original to use the app as it is.",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
            }
        }
    }
    // The app only shows a window of its list: as the Sidekit list nears its end, scroll the app on.
    val canMore by rememberUpdatedState(model.list?.canScrollForward == true)
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }.distinctUntilChanged().filter { it && canMore }.collect { actions.loadMore(forward = true) }
    }
    LaunchedEffect(state.items.size) {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (canMore && last >= info.totalItemsCount - LOAD_AHEAD) actions.loadMore(forward = true)
    }
}

private const val LOAD_AHEAD = 3

private fun LazyListScope.blocks(blocks: List<Block>, prefix: String, start: Int, list: LazyListState, actions: ProjectionActions): Int {
    itemsIndexed(blocks, key = { _, b -> "$prefix.${b.key}" }) { i, block ->
        BlockRow(block, Modifier.listFade(list, start + i), actions)
    }
    return start + blocks.size
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockRow(block: Block, modifier: Modifier, actions: ProjectionActions) {
    when (block) {
        is TextBlock -> if (block.heading) {
            SidekitText(block.text, modifier.padding(horizontal = SidekitTheme.spacing.itemPadding), style = SidekitTheme.typography.headline, maxLines = 2)
        } else {
            SidekitCard(modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SidekitText(block.text, style = SidekitTheme.typography.bodySmall, maxLines = 12)
                    block.detail?.let { SidekitText(it, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6, maxLines = 2) }
                }
            }
        }
        is ItemBlock -> {
            val secondary = block.checked?.let { if (it) "On" else "Off" } ?: block.secondary
            val colour = when {
                !block.enabled -> ItemColour.Muted
                block.selected || block.checked == true -> ItemColour.Secondary
                else -> ItemColour.Primary
            }
            if (block.longClick != null) {
                SidekitItem(
                    block.title,
                    modifier.combinedClickable(
                        onClick = { actions.press(block) },
                        onLongClick = { actions.press(block, long = true) },
                        onLongClickLabel = "More for ${block.title}",
                    ),
                    secondary = secondary,
                    colour = colour,
                )
            } else {
                SidekitItem(
                    block.title,
                    modifier,
                    secondary = secondary,
                    colour = colour,
                    showLinkArrow = block.click != null && secondary == null && block.inList,
                    onClick = block.click?.let { { actions.press(block) } },
                )
            }
        }
        is FieldBlock -> ProjectedField(block, modifier, actions)
        is ButtonBlock -> SidekitLineButton(
            block.label,
            onClick = { actions.press(block) },
            modifier = modifier,
            variant = when (block.tone) {
                Tone.Destructive -> LineButtonVariant.Destructive
                Tone.Muted -> LineButtonVariant.Secondary
                else -> LineButtonVariant.Primary
            },
        )
    }
}

/**
 * A field of the app, typed in Sidekit (T9 or touch) and copied into the app as you type. While
 * the field has focus the app's echo of the text is ignored, so a slow round trip can't undo keys.
 */
@OptIn(FlowPreview::class)
@Composable
private fun ProjectedField(block: FieldBlock, modifier: Modifier, actions: ProjectionActions) {
    var text by remember(block.key) { mutableStateOf(block.text) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(block.text) { if (!focused) text = block.text }
    LaunchedEffect(block.key) {
        snapshotFlow { text }.drop(1).debounce(FIELD_SYNC_MS).collect { actions.setText(block.key, it) }
    }
    SidekitTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        placeholder = block.hint ?: if (block.password) "Password" else null,
        singleLine = true,
    )
}

private const val FIELD_SYNC_MS = 250L

private fun Tone.actionTone() = when (this) {
    Tone.Primary -> ActionTone.Primary
    Tone.Secondary -> ActionTone.Secondary
    Tone.Destructive -> ActionTone.Destructive
    Tone.Muted -> ActionTone.Muted
}

/** Back, then the app's (at most two) actions. Back is a full button when it's alone. */
private fun actionBar(specs: List<ActionSpec>, actions: ProjectionActions): List<SideAction> {
    if (specs.isEmpty()) return listOf(SideAction.back("back", "Back", actions::back))
    return listOf(SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", actions::back, tone = ActionTone.Muted, motion = ActionMotion.Back)) +
        specs.map { spec ->
            SideAction("a.${spec.key}", spec.label, tone = spec.tone.actionTone(), motion = ActionMotion.Forward, weight = 1f) {
                actions.pressAction(spec.handle, spec.key)
            }
        }
}

/** Everything that didn't fit the bar: the app's menu and tabs, then Sideport's own controls. */
@Composable
private fun MenuScreen(state: ProjectionState, model: ScreenModel, actions: ProjectionActions, close: () -> Unit) {
    val list = rememberLazyListState()
    val rows = buildList<Pair<String, () -> Unit>> {
        model.options.forEach { o -> add((if (o.selected) "• ${o.label}" else o.label) to { close(); actions.pressAction(o.handle, o.key) }) }
    }
    val sideport = listOf<Pair<String, () -> Unit>>(
        "Show original" to { close(); actions.showOriginal() },
        "Messages and media" to { close(); actions.openHub() },
        "Inspect screen" to { close(); actions.toggleInspector() },
        "Back to Sidekit" to { close(); actions.exitToSidekit() },
    )
    SidekitScaffold("${state.app.label} menu", actions = listOf(SideAction.back("menu.close", "Close", close))) { padding ->
        SidekitList(padding, state = list) {
            itemsIndexed(rows) { i, (label, run) -> SidekitItem(label, Modifier.listFade(list, i), onClick = run) }
            itemsIndexed(sideport) { i, (label, run) ->
                SidekitItem(label, Modifier.listFade(list, rows.size + i), colour = ItemColour.Muted, onClick = run)
            }
        }
    }
}

/** The raw tree under the projection, for writing recipes. Export saves it as JSON. */
@Composable
private fun InspectorScreen(state: ProjectionState, actions: ProjectionActions) {
    val tree = state.tree
    val rows = remember(tree) { tree?.let(::flatten).orEmpty() }
    val model = state.model
    val list = rememberLazyListState()
    SidekitScaffold(
        "Inspect",
        actions = listOf(
            SideAction.back("inspect.close", "Close", actions::toggleInspector),
            SideAction("inspect.export", "Export", tone = ActionTone.Secondary, motion = ActionMotion.Up, onClick = actions::exportTree),
        ),
    ) { padding ->
        SidekitList(padding, state = list) {
            item(key = "summary") {
                SidekitCard(Modifier.fillMaxWidth()) {
                    SidekitText(
                        buildString {
                            append(model?.packageName ?: state.app.packageName)
                            append("\n").append(model?.signature?.substringAfter('|')?.substringBefore('|')?.ifEmpty { null } ?: "unknown screen")
                            append("\nRecipe: ").append(model?.recipe ?: "none (generic)")
                            append("\n").append(rows.size).append(" nodes")
                            if ((model?.unlabelled ?: 0) > 0) append(", ").append(model?.unlabelled).append(" unlabelled buttons")
                            state.notice?.let { append("\n").append(it) }
                        },
                        style = SidekitTheme.typography.caption,
                        maxLines = 8,
                    )
                }
            }
            itemsIndexed(rows, key = { _, r -> r.first.handle }) { i, (node, depth) ->
                SidekitCard(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(8) * 6).dp).listFade(list, i + 1)) {
                    Column {
                        SidekitText(
                            node.simpleClass + (node.idName?.let { " #$it" } ?: ""),
                            style = SidekitTheme.typography.tiny,
                            color = SidekitTheme.colors.surfaceA6,
                        )
                        node.label?.let { SidekitText(it, style = SidekitTheme.typography.small, maxLines = 3) }
                        flags(node).takeIf { it.isNotEmpty() }?.let {
                            SidekitText(it, style = SidekitTheme.typography.tiny, color = SidekitTheme.colors.primaryA3)
                        }
                    }
                }
            }
        }
    }
}

private fun flatten(root: UiNode): List<Pair<UiNode, Int>> {
    val out = ArrayList<Pair<UiNode, Int>>()
    fun go(n: UiNode, d: Int) { out += n to d; n.children.forEach { go(it, d + 1) } }
    go(root, 0)
    return out
}

private fun flags(n: UiNode) = buildList {
    if (n.clickable) add("click")
    if (n.longClickable) add("long")
    if (n.editable) add("edit")
    if (n.scrollable) add("scroll")
    if (n.checkable) add(if (n.checked) "on" else "off")
    if (n.selected) add("selected")
    if (n.heading) add("heading")
}.joinToString(" · ")

/** Shown over an app in its original look: one tap returns to the Sidekit projection. */
@Composable
fun OriginalPill(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(SidekitTheme.colors.primaryA1)
            .clickable(onClickLabel = "Show $label in Sidekit", onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SidekitIconImage(SidekitIcon.DeployedCode, size = 22.dp)
        SidekitText("Sidekit", style = SidekitTheme.typography.small)
    }
}
