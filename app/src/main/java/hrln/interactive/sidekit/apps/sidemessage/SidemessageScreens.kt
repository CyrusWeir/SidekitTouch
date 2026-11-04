package hrln.interactive.sidekit.apps.sidemessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.apps.phonebook.ContactMatch
import hrln.interactive.sidekit.apps.phonebook.PhoneFormat
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

object SidemessageTags {
    const val INBOX = "sidemessage_inbox"
    const val CONVERSATION = "sidemessage_conversation"
    const val TO = "sidemessage_to"
}

/**
 * "sidemessage home" (35:98): pinned contacts as tiles, then conversations, newest first. Unread
 * conversations are tinted blue.
 *
 * Bar: "New Message →". Without SMS access it splits into "Allow | New →"; when Sidekit isn't the
 * default SMS app, into "Make default | New →".
 */
@Composable
fun SidemessageInboxScreen(
    state: InboxUiState,
    canBecomeDefault: Boolean,
    onAllow: () -> Unit,
    onMakeDefault: () -> Unit,
    onOpen: (ThreadSummary) -> Unit,
    onOpenPinned: (PinnedTile) -> Unit,
    onNewMessage: () -> Unit,
    contactsAllowed: Boolean = true,
    onAllowContacts: () -> Unit = {},
) {
    val access = state.access
    val offerDefault = !access.isDefault && canBecomeDefault
    val actions = when {
        !access.canRead -> listOf(
            SideAction("left", "Allow", tone = ActionTone.Primary, motion = ActionMotion.Up, contentDescription = "Allow SMS", onClick = onAllow),
            SideAction("new", "New", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "New Message", onClick = onNewMessage),
        )
        offerDefault -> listOf(
            SideAction("left", "Make default", tone = ActionTone.Muted, weight = 1.2f, motion = ActionMotion.Up, onClick = onMakeDefault),
            SideAction("new", "New", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "New Message", onClick = onNewMessage),
        )
        else -> listOf(SideAction.forward("new", "New Message", onNewMessage))
    }
    SidekitScaffold(
        title = "Sidemessage",
        actions = actions,
        options = listOfNotNull(
            if (offerDefault) SideAction("default", "Make default", onClick = onMakeDefault) else null,
            if (!contactsAllowed) SideAction("contacts", "Allow contacts", onClick = onAllowContacts) else null,
        ),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, Modifier.testTag(SidemessageTags.INBOX), state = listState) {
            var index = 0
            if (!access.canRead) {
                val i = index++
                item("permission") {
                    InfoCard(
                        "Allow text access to read conversations. To receive texts here, choose Make default in Options.",
                        Modifier.listFade(listState, i),
                    )
                }
            } else if (offerDefault) {
                val i = index++
                item("default") {
                    InfoCard(
                        "Sidemessage isn't your default SMS app, so new texts go to your other app first and can't be marked read here. Reading and sending still work.",
                        Modifier.listFade(listState, i),
                        onClick = onMakeDefault,
                    )
                }
            }
            if (!contactsAllowed && access.canRead) {
                val i = index++
                item("contacts") { InfoCard("Allow contacts from Options to show names beside your texts.", Modifier.listFade(listState, i), onAllowContacts) }
            }
            state.pinned.chunked(2).forEachIndexed { r, row ->
                val i = index++
                item("pinned-$r") {
                    SidekitItemRow(Modifier.listFade(listState, i)) {
                        row.forEach { tile ->
                            SidekitTile(
                                Modifier.weight(1f),
                                color = if (tile.thread?.unread == true) ItemColour.Secondary.container() else ItemColour.Primary.container(),
                                onClick = { onOpenPinned(tile) },
                            ) {
                                SidekitText(tile.name, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
            if (access.canRead && state.loaded && state.threads.isEmpty() && state.pinned.isEmpty()) {
                val i = index++
                item("empty") { InfoCard("No conversations yet", Modifier.listFade(listState, i)) }
            }
            val offset = index
            itemsIndexed(state.threads, key = { _, t -> t.id }, contentType = { _, _ -> 0 }) { i, thread ->
                SidekitItem(
                    title = thread.name,
                    secondary = thread.snippet,
                    colour = if (thread.unread) ItemColour.Secondary else ItemColour.Primary,
                    modifier = Modifier.listFade(listState, i + offset),
                    onClick = { onOpen(thread) },
                )
            }
        }
    }
}

/**
 * "text" (35:145) and "text - typing" (35:268). Messages appear newest first; the Older messages row
 * pages through the history. Sent messages say whether they're sending, sent, delivered or failed
 * (tap a failed one to send it again).
 *
 * Options: pin/unpin, call, make default.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    canBecomeDefault: Boolean,
    onBack: () -> Unit,
    onAllow: () -> Unit,
    onStartTyping: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (Message) -> Unit,
    onLoadOlder: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onCall: (() -> Unit)?,
    onMakeDefault: () -> Unit,
    onLoadLatest: () -> Unit = {},
    onAttach: (() -> Unit)? = null,
    attachmentNotice: String? = null,
) {
    val draft = state.draft
    val actions = buildList {
        if (!state.access.canRead && draft == null) {
            add(SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, motion = ActionMotion.Back))
            add(SideAction("allow", "Allow", tone = ActionTone.Primary, motion = ActionMotion.Up, contentDescription = "Allow SMS", onClick = onAllow))
        } else {
            add(SideAction.back("back", "Back", onBack))
        }
        add(
            if (draft == null) SideAction("compose", "Text", SidekitIcon.ArrowUpward, iconAtEnd = true, motion = ActionMotion.Up, onClick = onStartTyping)
            else SideAction("compose", "Send", SidekitIcon.ArrowUpward, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Send message", onClick = onSend),
        )
    }
    val options = listOfNotNull(
        onAttach?.let { SideAction("attachment", "Attach MMS", onClick = it) },
        onTogglePin?.let { SideAction("pin", if (state.pinned) "Unpin" else "Pin", onClick = it) },
        onCall?.let { SideAction("call", "Call", SidekitIcon.Call, onClick = it) },
        if (!state.access.isDefault && canBecomeDefault) SideAction("default", "Make default", onClick = onMakeDefault) else null,
    )
    SidekitScaffold(title = state.title, actions = actions, options = options) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap)) {
            attachmentNotice?.let { SidekitText(it, maxLines = 3) }
            val listState = rememberLazyListState()
            val messages = state.messages
            // Status under the newest sent message, and under anything still sending or failed.
            val newestMine = remember(messages) { messages.indexOfFirst { it.fromMe } }
            if (!state.access.canRead) {
                InfoCard("Allow SMS access to see this conversation. You can still send a text.", Modifier.fillMaxWidth())
            }
            SidekitList(
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).testTag(SidemessageTags.CONVERSATION),
                state = listState,
            ) {
                if (state.hasNewer) item("latest") {
                    InfoCard("Back to latest messages", onClick = onLoadLatest)
                }
                itemsIndexed(messages, key = { i, m -> m.key.ifEmpty { "i$i" } }, contentType = { _, m -> m.fromMe }) { i, message ->
                    val showStatus = message.fromMe && (i == newestMine || message.delivery == Delivery.Sending || message.delivery == Delivery.Failed)
                    Bubble(message, showStatus, onRetry, Modifier.listFade(listState, i + if (state.hasNewer) 1 else 0))
                }
                if (state.hasMore) item("older") {
                    InfoCard("Older messages", onClick = onLoadOlder)
                }
            }
            if (draft != null) ComposeBox(draft, onDraftChange)
        }
    }
}


/** Received bubbles hug the left edge in surface grey; sent ones the right in primary-a2. Max width 400 (35:215). */
@Composable
private fun Bubble(message: Message, showStatus: Boolean, onRetry: (Message) -> Unit, modifier: Modifier) {
    val failed = message.fromMe && message.delivery == Delivery.Failed
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mmsNotice by remember { mutableStateOf<String?>(null) }
    fun retryMms() {
        scope.launch {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MmsTransport.retry(context, android.net.Uri.parse(message.pendingMms)) }; mmsNotice = "Download requested" }
            catch (_: Exception) { mmsNotice = "Could not retry MMS" }
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
        SidekitCard(
            modifier = Modifier.widthIn(max = 400.dp),
            color = when {
                failed -> ItemColour.Destructive.container()
                message.fromMe -> ItemColour.Secondary.container()
                else -> ItemColour.Primary.container()
            },
            onClick = if (message.pendingMms != null) ({ retryMms() }) else if (failed) ({ onRetry(message) }) else null,
        ) {
            Column {
                SidekitText(mmsNotice ?: message.text, maxLines = Int.MAX_VALUE)
                message.attachments.forEach { MmsAttachmentView(it) }
            }
        }
        if (showStatus && message.delivery != Delivery.None) {
            SidekitText(
                when (message.delivery) {
                    Delivery.Sending -> "Sending…"
                    Delivery.Sent -> "Sent"
                    Delivery.Delivered -> "Delivered"
                    Delivery.Failed -> "Not sent · tap to retry"
                    Delivery.None -> ""
                },
                style = SidekitTheme.typography.small,
                color = if (failed) SidekitTheme.colors.dangerA3 else SidekitTheme.colors.surfaceA6,
                modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding, vertical = 4.dp),
            )
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

/**
 * New Message: type a name or number (T9 on the keypad) in the "To" field; matching contacts list
 * below it, and a typed number gets its own row. The bar offers "Text →" once a number is typed.
 */
@Composable
fun NewMessageScreen(
    state: NewMessageUiState,
    region: String,
    onQuery: (String) -> Unit,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onAllowContacts: () -> Unit,
) {
    val number = state.number
    val actions = listOfNotNull(
        SideAction.back("back", "Back", onBack),
        when {
            number != null -> SideAction("next", "Text", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "Text $number") { onPick(number) }
            !state.contactsAllowed -> SideAction("next", "Contacts", tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Allow contacts", onClick = onAllowContacts)
            else -> null
        },
    )
    SidekitScaffold(title = "New Message", actions = actions) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item("to") {
                SidekitCard(Modifier.fillMaxWidth().listFade(listState, 0)) {
                    SidekitTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth().testTag(SidemessageTags.TO),
                        placeholder = "To: name or number",
                        focusOnKeypad = true,
                        singleLine = true,
                        autoFocus = true,
                        onDone = { number?.let(onPick) },
                    )
                }
            }
            var index = 1
            if (number != null) {
                val i = index++
                item("number") {
                    SidekitItem(
                        title = "Text",
                        secondary = PhoneFormat.format(number, region),
                        icon = SidekitIcon.Chat,
                        colour = ItemColour.Secondary,
                        modifier = Modifier.listFade(listState, i),
                        onClick = { onPick(number) },
                    )
                }
            }
            if (!state.contactsAllowed) {
                val i = index++
                item("permission") {
                    InfoCard("Allow contacts to pick who to text by name.", Modifier.listFade(listState, i), onClick = onAllowContacts)
                }
                return@SidekitList
            }
            val offset = index
            itemsIndexed(state.matches, key = { _, m -> m.contact.id }, contentType = { _, _ -> 0 }) { i, m ->
                ContactRow(m, region, Modifier.listFade(listState, i + offset), onPick)
            }
        }
    }
}

@Composable
private fun ContactRow(match: ContactMatch, region: String, modifier: Modifier, onPick: (String) -> Unit) {
    val number = match.number ?: match.contact.primaryNumber
    SidekitItem(
        title = match.contact.name,
        secondary = if (match.contact.numbers.size > 1 || match.number != null) PhoneFormat.format(number.number, region) else null,
        modifier = modifier,
        onClick = { onPick(number.number) },
    )
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    SidekitCard(modifier.fillMaxWidth(), color = ItemColour.Muted.container(), onClick = onClick) {
        SidekitText(text, style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
    }
}

object SidemessageSampleData {
    // From "sidemessage home" (35:98) and "text" (35:145).
    private fun t(id: Long, name: String, snippet: String) = ThreadSummary(id, listOf("04000000$id"), name, snippet, 1_760_000_000_000L - id, unread = false)

    val inbox = InboxUiState(
        access = SmsAccess(canRead = true, isDefault = true),
        loaded = true,
        pinned = listOf(PinnedTile("0400000001", "Amelie", t(1, "Amelie", "Yeah i’ll come over soon")), PinnedTile("0400000002", "Mum", null)),
        threads = listOf(
            t(3, "Josie", "Yeah should be all good"),
            t(4, "Ari", "Dont forget to go over to the"),
            t(5, "Fio", "Its on sunday so come whenever"),
        ),
    )

    val conversation = ConversationUiState(
        title = "Amelie",
        access = SmsAccess(canRead = true, isDefault = true),
        loaded = true,
        messages = listOf(
            Message("Yeah i’ll come over soon", fromMe = true, date = 3, delivery = Delivery.Delivered, key = "s3"),
            Message("do u wanna do something later?", fromMe = false, date = 2, key = "s2"),
            Message("Im free tn", fromMe = false, date = 1, key = "s1"),
        ),
    )
}

@SidephonePreview
@Composable
private fun InboxPreview() = SidekitPreviewFrame {
    SidemessageInboxScreen(SidemessageSampleData.inbox, true, {}, {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun ConversationPreview() = SidekitPreviewFrame {
    ConversationScreen(SidemessageSampleData.conversation, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun NotDefaultPreview() = SidekitPreviewFrame {
    SidemessageInboxScreen(SidemessageSampleData.inbox.copy(access = SmsAccess(canRead = true, isDefault = false)), true, {}, {}, {}, {}, {})
}
