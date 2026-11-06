package hrln.interactive.sidekit.apps.settings

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * Reordering is a selection mode, keypad-first: Confirm on a focused app selects it (highlighted),
 * then Up/Down moves it through the order. The selected row also shows up/down arrows on its right
 * as a direct toggle, and tapping a row selects it too. The dock is automatic — the first
 * [HomePreferences.DOCK_MAX] apps are "In dock" — so reordering an app into/out of the top four
 * pins/unpins it. Hidden apps are omitted so they cannot take a dock slot here.
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
    fun done() {
        arranger.commit()
        back()
    }
    fun cancel() = back() // buffer never committed, so Back leaves the launcher unchanged

    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Moving the selected app reorders the keyed row, but focus alone does not re-run bring-into-view.
    // Keep the list on the selected app so Up/Down never walks the selection off the visible page.
    LaunchedEffect(selected, order) {
        val index = selected?.let(order::indexOf) ?: return@LaunchedEffect
        if (index >= 0) list.animateScrollToItem(index + 1)
    }
    SidekitScaffold(
        "Arrange apps",
        // While an app is selected, Up/Down move it instead of moving focus (B/T on the keypad);
        // Left/Right, Confirm and Back are untouched so the user can still reach the arrows and the
        // action bar. Deselect (Confirm on the selected row) to browse freely again.
        modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (selected != null && native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { arranger.moveSelected(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { arranger.moveSelected(+1); true }
                    else -> false
                }
            } else false
        }.focusable(),
        actions = listOf(
            SideAction.back("back", "Settings", ::cancel),
            SideAction.forward("done", "Done", ::done, icon = SidekitIcon.Check),
            SideAction("reset", "Reset", icon = SidekitIcon.Undo, tone = ActionTone.Muted, onClick = { arranger.reset() }),
        ),
    ) { padding ->
        SidekitList(padding, state = list) {
            item {
                SidekitText(
                    "Select apps, then move them up or down. First 4 apps are also shown in the dock",
                    style = SidekitTheme.typography.label,
                    maxLines = Int.MAX_VALUE,
                )
            }
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
                        modifier = Modifier.weight(1f),
                        secondary = when {
                            isSelected -> "Selected"
                            arranger.isInDock(id) -> "In dock"
                            else -> null
                        },
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
