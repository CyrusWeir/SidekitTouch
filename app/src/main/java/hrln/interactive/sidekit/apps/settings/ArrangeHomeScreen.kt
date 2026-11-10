package hrln.interactive.sidekit.apps.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.home.HomeArranger
import hrln.interactive.sidekit.home.HomePreferences
import hrln.interactive.sidekit.navigation.SubApp

/**
 * Settings → Arrange apps. Edit the home order against a local buffer and commit only on Done, so
 * Back genuinely cancels and Reset only touches the buffer.
 *
 * Up/Down walk the app list like Home. Reordering is a selection mode, keypad-first: Confirm on a
 * focused app selects it (highlighted), then Up/Down moves it through the order. The selected row
 * also shows up/down arrows on its right as a direct toggle, and tapping a row selects it too. The
 * dock is automatic — the first [HomePreferences.DOCK_MAX] apps are "In dock" — so reordering an
 * app into/out of the top four pins/unpins it. Hidden apps are omitted so they cannot take a dock
 * slot here.
 */
@Composable
internal fun ArrangeHomeScreen(back: () -> Unit) {
    val context = LocalContext.current
    val home = remember(context) { HomePreferences(context) }
    val prefs = remember(context) { SidekitPreferences(context) }
    val hidden = rememberHiddenApps(prefs)
    val ported by remember { PortedApps.get(context).apps }.collectAsStateWithLifecycle()
    val subsById = remember(SubApp.entries) { SubApp.entries.associateBy { it.visibilityId() } }
    val portedById = remember(ported) { ported.associateBy { it.visibilityId() } }
    val known = remember(subsById, portedById, hidden) { (subsById.keys + portedById.keys) - hidden }
    // Editing buffer, seeded from the stored prefs once. Nothing is written until commit() (Done).
    val arranger = remember(home, known) { HomeArranger(home, known) }
    val order = arranger.order
    val selected = arranger.selected
    var confirmingDiscard by remember { mutableStateOf(false) }
    fun done() {
        arranger.commit()
        back()
    }
    fun cancel() = back() // buffer never committed, so Back leaves the launcher unchanged
    fun requestBack() {
        if (arranger.hasPendingChanges) confirmingDiscard = true else cancel()
    }
    fun discard() {
        confirmingDiscard = false
        cancel()
    }
    BackHandler {
        if (confirmingDiscard) confirmingDiscard = false else requestBack()
    }

    // Start on the first app, not on the screen itself: a focused screen has no D-pad target, so
    // Up/Down would do nothing. A frame is needed because LazyColumn composes rows during layout.
    val firstRow = remember { FocusRequester() }
    val selectedRow = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstRow.requestFocus() } }
    // Keep the selected app in context while it moves: at least two rows above and below it stay
    // visible, so the user can judge its position instead of it sticking to the top of the list.
    // Only scroll when that context is missing, and put the selected row third from the top.
    LaunchedEffect(selected, order) {
        val id = selected ?: return@LaunchedEffect
        val index = order.indexOf(id)
        if (index < 0) return@LaunchedEffect
        val listIndex = index + 1
        val visible = list.layoutInfo.visibleItemsInfo
        val selectedVisible = visible.any { it.index == listIndex }
        val above = visible.count { it.index < listIndex }
        val below = visible.count { it.index > listIndex }
        if (!selectedVisible || above < 2 || below < 2) {
            list.scrollToItem((listIndex - 2).coerceAtLeast(0))
            withFrameNanos { }
        }
        // Reordering moves the keyed row; focus can be dropped even though the row still looks
        // selected. Put it back so holding Up/Down never leaves the highlight behind.
        runCatching { selectedRow.requestFocus() }
    }
    val actions = if (confirmingDiscard) {
        listOf(
            SideAction.cancel("cancel", "Cancel", onClick = { confirmingDiscard = false }),
            SideAction("discard", "Discard", icon = SidekitIcon.Undo, tone = ActionTone.Destructive, motion = ActionMotion.Up, onClick = ::discard),
        )
    } else {
        listOf(
            SideAction.back("back", "Settings", ::requestBack),
            SideAction.forward("done", "Done", ::done, icon = SidekitIcon.Check),
        )
    }
    val options = if (confirmingDiscard) emptyList() else listOf(
        SideAction("reset", "Reset", icon = SidekitIcon.Undo, tone = ActionTone.Muted, onClick = { arranger.reset() }),
    )
    SidekitScaffold(
        "Arrange apps",
        // With nothing selected, Up/Down are left to focus traversal, so the keypad walks the app
        // list like Home. Once an app is selected, Up/Down move that app instead (T/B on the
        // keypad); Left/Right, Confirm and Back are untouched so the user can still reach the
        // arrows and the action bar. Deselect (Confirm on the selected row) to browse freely again.
        // Repeats are consumed too: otherwise holding a direction walks focus away while the row
        // stays selected, leaving the highlight and the actual focus out of sync.
        modifier = Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (selected != null && !confirmingDiscard && native.action == KeyEvent.ACTION_DOWN) {
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { arranger.moveSelected(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { arranger.moveSelected(+1); true }
                    else -> false
                }
            } else false
        },
        actions = actions,
        options = options,
    ) { padding ->
        SidekitList(padding, state = list, focusBandSlots = 2) {
            item { ArrangeHint() }
            val last = order.lastIndex
            itemsIndexed(order, key = { _, id -> id }) { index, id ->
                val label = subsById[id]?.label ?: portedById[id]?.label ?: return@itemsIndexed
                val enabledUp = index > 0
                val enabledDown = index < last
                val isSelected = arranger.isSelected(id)
                // Arrows only appear on the selected row, on its right, so unselected rows stay clean
                // and every arrow is an unambiguous D-pad/touch target for the app it moves.
                Row(
                    Modifier.fillMaxWidth().height(76.dp).listFade(list, index + 1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
                ) {
                    SidekitItem(
                        title = label,
                        modifier = Modifier.weight(1f).then(
                            when {
                                isSelected -> Modifier.focusRequester(selectedRow)
                                index == 0 -> Modifier.focusRequester(firstRow)
                                else -> Modifier
                            },
                        ),
                        secondary = if (isSelected) "Selected" else null,
                        colour = if (isSelected) ItemColour.Secondary else ItemColour.Primary,
                        onClick = { arranger.select(id) },
                    )
                    if (isSelected) {
                        Column {
                            ItemArrow(up = true, enabled = enabledUp, contentDescription = "Move $label up") { arranger.move(id, -1) }
                            ItemArrow(up = false, enabled = enabledDown, contentDescription = "Move $label down") { arranger.move(id, +1) }
                        }
                    }
                }
            }
        }
    }
}

/** The keypad hint, with key names in small grey caps so they read as keys, not prose. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrangeHint() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap("Enter")
        SidekitText(": Select App,", style = SidekitTheme.typography.label)
        KeyCap("Up")
        SidekitText("/", style = SidekitTheme.typography.label)
        KeyCap("Down")
        SidekitText(": Move App", style = SidekitTheme.typography.label)
    }
}

@Composable
private fun KeyCap(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SidekitTheme.colors.surfaceA3)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        SidekitText(label, style = SidekitTheme.typography.small, color = SidekitTheme.colors.light)
    }
}

/** A small round arrow button; `up=false` flips the upward glyph to point down. Disabled state is dimmed. */
@Composable
private fun ItemArrow(up: Boolean, enabled: Boolean, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .alpha(if (enabled) 1f else 0.3f)
            .then(if (enabled) Modifier.clickable(onClickLabel = contentDescription, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        SidekitIconImage(
            SidekitIcon.ArrowUpward,
            contentDescription = contentDescription,
            modifier = if (up) Modifier else Modifier.graphicsLayer { rotationZ = 180f },
        )
    }
}
