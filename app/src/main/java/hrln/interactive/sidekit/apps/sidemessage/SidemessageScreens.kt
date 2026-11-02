package hrln.interactive.sidekit.apps.sidemessage

import androidx.compose.foundation.layout.Arrangement
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

/** "sidemessage home" (35:98): pinned contacts row, then conversations. */
@Composable
fun SidemessageInboxScreen(
    pinned: List<String>,
    threads: List<Conversation>,
    onOpen: (String) -> Unit,
    onNewMessage: () -> Unit,
) {
    SidekitScaffold(
        title = "Sidemessage",
        actions = listOf(SideAction.forward("new", "New Message", onNewMessage)),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            if (pinned.isNotEmpty()) item("pinned") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    pinned.forEach { contact ->
                        SidekitTile(Modifier.weight(1f), onClick = { onOpen(contact) }) {
                            SidekitText(contact, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            val offset = if (pinned.isNotEmpty()) 1 else 0
            itemsIndexed(threads, key = { _, c -> c.contact }) { index, conversation ->
                SidekitItem(
                    title = conversation.contact,
                    secondary = conversation.preview,
                    modifier = Modifier.listFade(listState, index + offset),
                    onClick = { onOpen(conversation.contact) },
                )
            }
        }
    }
}

/** "text" (35:145) and "text - typing" (35:268). */
@Composable
fun ConversationScreen(
    conversation: Conversation,
    draft: String?,
    onBack: () -> Unit,
    onStartTyping: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    SidekitScaffold(
        title = conversation.contact,
        actions = listOf(
            SideAction.back("back", "Back", onBack),
            if (draft == null) SideAction("compose", "Text", SidekitIcon.ArrowUpward, iconAtEnd = true, motion = ActionMotion.Up, onClick = onStartTyping)
            else SideAction("compose", "Send", SidekitIcon.ArrowUpward, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, onClick = onSend),
        ),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap)) {
            // Newest message sits just above the action bar / compose box.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap),
                contentPadding = PaddingValues(0.dp),
            ) {
                itemsIndexed(conversation.messages.asReversed()) { _, message -> Bubble(message) }
            }
            if (draft != null) ComposeBox(draft, onDraftChange)
        }
    }
}

/** Received bubbles hug the left edge in surface grey; sent ones the right in primary-a2. Max width 400 (35:215). */
@Composable
private fun Bubble(message: Message) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        SidekitCard(
            modifier = Modifier.widthIn(max = 400.dp),
            color = if (message.fromMe) ItemColour.Secondary.container() else ItemColour.Primary.container(),
        ) {
            SidekitText(message.text, maxLines = Int.MAX_VALUE)
        }
    }
}

/** The 148-high compose card above the action bar (35:278). */
@Composable
private fun ComposeBox(text: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    SidekitCard(Modifier.fillMaxWidth().height(148.dp)) {
        SidekitTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxSize(),
            focusRequester = focus,
            focusOnKeypad = true,
            autoFocus = true,
        )
    }
}

@SidephonePreview
@Composable
private fun InboxPreview() = SidekitPreviewFrame {
    val state = SidemessageUiState()
    SidemessageInboxScreen(state.pinned, state.threads, {}, {})
}

@SidephonePreview
@Composable
private fun ConversationPreview() = SidekitPreviewFrame {
    ConversationScreen(SidemessageUiState().conversation("Amelie"), draft = null, {}, {}, {}, {})
}
