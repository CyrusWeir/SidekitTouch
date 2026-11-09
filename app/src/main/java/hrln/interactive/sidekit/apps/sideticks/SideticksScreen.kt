package hrln.interactive.sidekit.apps.sideticks

import androidx.compose.foundation.layout.Arrangement
import hrln.interactive.sidekit.design.components.SidekitSwipeRow
import hrln.interactive.sidekit.design.components.rememberSwipeRevealGroup
import java.time.Instant
import java.time.ZoneId
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextDecoration
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTickBox
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

/**
 * My Sideticks: "No ticks" (1:510), "Adding Tick" (1:640), "Scroll to bottom" (1:534, the
 * "Show Ticked" row), "show ticked" / "Expanded view" (1:564, 1:600).
 */
@Composable
fun SideticksScreen(
    state: SideticksUiState,
    onToggle: (Tick) -> Unit,
    onShowTickedChange: (Boolean) -> Unit,
    onClearTicked: () -> Unit,
    onNewTick: () -> Unit,
    onDraftChange: (String) -> Unit,
    onConfirmDraft: () -> Unit,
    onDelete: (Tick) -> Unit = {},
    onUndo: () -> Unit = {},
    onDue: (String?) -> Unit = {},
    onSort: () -> Unit = {},
    onCancel: () -> Unit = {},
    onNotifications: () -> Unit = {},
    needsNotifications: Boolean = false,
) {
    val screenFocus = remember { FocusRequester() }
    SidekitScaffold(
        focusParking = screenFocus,
        title = "My Sideticks",
        actions = buildList {
            add(
            if (state.draft != null) SideAction("main", "Confirm", SidekitIcon.Check, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, onClick = onConfirmDraft)
            else SideAction("main", "New Sidetick", SidekitIcon.Add, iconAtEnd = true, motion = ActionMotion.Up, onClick = onNewTick))
            if (state.draft != null) add(SideAction("cancel", "Cancel", onClick = onCancel))
            else if (state.recentlyDeleted.isNotEmpty()) add(SideAction("undo", "Undo", tone = ActionTone.Muted, onClick = onUndo))
            if (needsNotifications && state.dueText != null) add(SideAction("allow", "Allow", onClick = onNotifications))
        },
        options = if (state.draft != null) listOf(SideAction("due", if (state.dueText == null) "Due time" else "No due time") { onDue(if (state.dueText == null) "" else null) })
        else listOf(SideAction("sort", if (state.byDue) "Newest first" else "Due first", onClick = onSort)),
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            var index = 0
            fun LazyListScope.row(key: Any, content: @Composable (Modifier) -> Unit) {
                val i = index++
                item(key) { content(Modifier.listFade(listState, i)) }
            }
            state.error?.let { message -> row("error") { SidekitItem(message, it) } }
            if (!state.loaded) row("loading") { SidekitItem("Loading reminders…", it) }
            if (needsNotifications && state.dueText != null) row("permission") { SidekitItem("Allow notifications for due reminders", it) }
            if (state.dueText != null) row("due") { modifier ->
                SidekitCard(modifier.fillMaxWidth()) {
                    SidekitTextField(state.dueText, { onDue(it) }, placeholder = "YYYY-MM-DD HH:mm", singleLine = true, dictation = false, onDone = { screenFocus.requestFocus() })
                }
            }
            if (state.draft != null) row("draft") { DraftRow(state.draft, onDraftChange, { screenFocus.requestFocus() }, it) }
            state.open.forEach { tick -> row(tick.id) { SidekitSwipeRow(tick.id, swipes, { onDelete(tick) }, it) { TickRow(tick, { onToggle(tick) }, Modifier) } } }
            if (state.ticked.isNotEmpty()) {
                if (!state.showTicked) {
                    row("show") { SidekitItem("Show Ticked", it, onClick = { onShowTickedChange(true) }) }
                } else {
                    row("hide") { SidekitItem("Hide Ticked", it, onClick = { onShowTickedChange(false) }) }
                    state.ticked.forEach { tick -> row(tick.id) { SidekitSwipeRow(tick.id, swipes, { onDelete(tick) }, it) { TickRow(tick, { onToggle(tick) }, Modifier) } } }
                    row("clear") { SidekitItem("Clear All Ticked", it, onClick = onClearTicked) }
                }
            }
        }
    }
}

/** A tick row grows to two lines for long titles ("Washing Machine" is 148 high in 1:594). */
@Composable
private fun TickRow(tick: Tick, onToggle: () -> Unit, modifier: Modifier) {
    SidekitCard(
        modifier = modifier.fillMaxWidth().heightIn(min = SidekitTheme.spacing.itemHeight),
        color = if (tick.ticked) ItemColour.Secondary.container() else ItemColour.Primary.container(),
        onClick = onToggle,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
        ) {
            SidekitTickBox(checked = tick.ticked)
            SidekitText(
                text = tick.title + (tick.dueAt?.let { "\n" + ReminderDateFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: ""),
                maxLines = 2,
                textDecoration = if (tick.ticked) TextDecoration.LineThrough else null,
            )
        }
    }
}

@Composable
private fun DraftRow(text: String, onChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    SidekitCard(modifier.fillMaxWidth().heightIn(min = SidekitTheme.spacing.itemHeight)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
        ) {
            SidekitTickBox(checked = false)
            SidekitTextField(
                value = text,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focus,
                focusOnKeypad = true,
                singleLine = true,
                onDone = onDone,
                autoFocus = true,
            )
        }
    }
}

@SidephonePreview
@Composable
private fun SideticksPreview() = SidekitPreviewFrame {
    SideticksScreen(SideticksUiState(showTicked = true), {}, {}, {}, {}, {}, {})
}
