package hrln.interactive.sidekit.apps.phonebook

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview
import java.util.Calendar

object PhonebookTags {
    const val CONTACTS = "phonebook_contacts"
    const val QUERY = "phonebook_query"
    const val DIAL_NUMBER = "phonebook_dial_number"
}

/**
 * Phonebook home: starred contacts as tiles, then everyone, alphabetical.
 *
 * Typing digits on the keypad searches like a Nokia: names that spell the keys and numbers that
 * contain them. The typed digits appear as the first row and the bar morphs from
 * "Recents | Dial →" into "Clear | Call", so a number can be dialled straight from here.
 */
@Composable
fun PhonebookHomeScreen(
    state: ContactsUiState,
    region: String,
    onAllow: () -> Unit,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onOpen: (Contact) -> Unit,
    onRecents: () -> Unit,
    onDialPad: () -> Unit,
    onCall: (String) -> Unit,
) {
    val searching = state.query.isNotEmpty()
    val actions = when {
        searching -> listOf(
            SideAction("left", "Clear", SidekitIcon.Close, tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Clear number", onClick = onClear),
            SideAction("right", "Call", SidekitIcon.Call, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Call ${state.query}") { onCall(state.query) },
        )
        !state.allowed -> listOf(
            SideAction("left", "Allow", tone = ActionTone.Primary, motion = ActionMotion.Up, contentDescription = "Allow contacts", onClick = onAllow),
            SideAction("right", "Dial", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "Dial pad", onClick = onDialPad),
        )
        else -> listOf(
            SideAction("left", "Calls", SidekitIcon.CallLog, motion = ActionMotion.Forward, contentDescription = "Recent calls", onClick = onRecents),
            SideAction("right", "Dial", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "Dial pad", onClick = onDialPad),
        )
    }
    val options = listOfNotNull(
        if (searching || !state.allowed) SideAction("recents", "Recents", SidekitIcon.CallLog, onClick = onRecents) else null,
        if (searching) SideAction("dial", "Dial pad", onClick = onDialPad) else null,
    )

    val parking = remember { FocusRequester() }
    SidekitScaffold(
        title = "Phonebook", actions = actions, options = options, focusParking = parking,
        modifier = Modifier.keypadDigits(onKey = { if (it in '0'..'9') onDigit(it) }, onDelete = onDelete, parking = parking),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(
            padding,
            Modifier.testTag(PhonebookTags.CONTACTS),
            state = listState,
        ) {
            var index = 0
            if (searching) {
                val i = index++
                item("query") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(listState, i).testTag(PhonebookTags.QUERY)) {
                        SidekitText(state.query, style = SidekitTheme.typography.headline, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (!state.allowed) {
                val i = index++
                item("permission") {
                    InfoCard(
                        "Phonebook needs your contacts to list them and search them from the keypad. You can still dial a number.",
                        Modifier.listFade(listState, i),
                    )
                }
                return@SidekitList
            }
            if (state.starred.isNotEmpty()) {
                state.starred.chunked(2).forEachIndexed { r, row ->
                    val i = index++
                    item("starred-$r") {
                        SidekitItemRow(Modifier.listFade(listState, i)) {
                            row.forEach { c ->
                                SidekitTile(Modifier.weight(1f), onClick = { onOpen(c) }) {
                                    SidekitText(c.name.substringBefore(' '), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (state.loaded && state.matches.isEmpty()) {
                val i = index++
                item("empty") {
                    InfoCard(if (searching) "No contacts match" else "No contacts with phone numbers yet", Modifier.listFade(listState, i))
                }
            }
            val offset = index
            itemsIndexed(state.matches, key = { _, m -> m.contact.id }, contentType = { _, _ -> 0 }) { i, m ->
                SidekitItem(
                    title = m.contact.name,
                    secondary = m.number?.let { PhoneFormat.format(it.number, region) },
                    modifier = Modifier.listFade(listState, i + offset),
                    onClick = { onOpen(m.contact) },
                )
            }
        }
    }
}

/**
 * A contact (or a bare number from the call log): one row per number, the chosen one highlighted.
 * The bar acts on the chosen number: "← | Call | Message".
 */
@Composable
fun ContactDetailScreen(
    title: String,
    numbers: List<PhoneNumber>,
    selected: Int,
    region: String,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val chosen = numbers.getOrNull(selected) ?: numbers.firstOrNull()
    SidekitScaffold(
        title = title,
        actions = listOfNotNull(
            SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, motion = ActionMotion.Back),
            chosen?.let { SideAction("call", "Call", SidekitIcon.Call, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Call ${it.number}") { onCall(it.number) } },
            chosen?.let { SideAction("message", "Text", SidekitIcon.Chat, motion = ActionMotion.Forward, contentDescription = "Message ${it.number}") { onMessage(it.number) } },
        ),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            itemsIndexed(numbers, key = { _, n -> n.digits + n.label }) { i, n ->
                SidekitItem(
                    title = n.label,
                    secondary = PhoneFormat.format(n.number, region),
                    colour = if (numbers.size > 1 && n == chosen) ItemColour.Secondary else ItemColour.Primary,
                    modifier = Modifier.listFade(listState, i),
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

/** Recent calls from the call log. Missed calls are red; tapping a row opens the contact or number. */
@Composable
fun RecentsScreen(
    state: RecentsUiState,
    region: String,
    onAllow: () -> Unit,
    onOpen: (CallRow) -> Unit,
    onBack: () -> Unit,
    onDialPad: () -> Unit,
) {
    SidekitScaffold(
        title = "Recents",
        actions = listOf(
            SideAction.back("back", "Back", onBack),
            if (state.allowed) SideAction("dial", "Dial", SidekitIcon.ArrowRightAlt, iconAtEnd = true, motion = ActionMotion.Forward, contentDescription = "Dial pad", onClick = onDialPad)
            else SideAction("dial", "Allow", tone = ActionTone.Primary, motion = ActionMotion.Up, contentDescription = "Allow call log", onClick = onAllow),
        ),
    ) { padding ->
        val listState = rememberLazyListState()
        val context = LocalContext.current
        val now = remember { System.currentTimeMillis() }
        SidekitList(padding, state = listState) {
            if (!state.allowed) {
                item("permission") { InfoCard("Recents needs your call log to show calls you've made and missed.", Modifier.listFade(listState, 0)) }
                return@SidekitList
            }
            if (state.loaded && state.calls.isEmpty()) {
                item("empty") { InfoCard("No calls yet", Modifier.listFade(listState, 0)) }
            }
            itemsIndexed(state.calls, key = { _, r -> r.call.id }, contentType = { _, _ -> 0 }) { i, row ->
                val missed = row.call.type == CallType.Missed || row.call.type == CallType.Rejected
                SidekitItem(
                    title = row.name ?: PhoneFormat.format(row.call.number, region).ifEmpty { "Unknown" },
                    secondary = CallTime.describe(row.call, now, DateFormat.is24HourFormat(context)),
                    colour = if (missed) ItemColour.Destructive else ItemColour.Primary,
                    modifier = Modifier.listFade(listState, i),
                    onClick = { onOpen(row) },
                )
            }
        }
    }
}

private val DIAL_KEYS = listOf(
    '1' to "", '2' to "abc", '3' to "def",
    '4' to "ghi", '5' to "jkl", '6' to "mno",
    '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
    '*' to "", '0' to "+", '#' to "",
)

/**
 * Dial pad: the number, then a 3 x 4 key grid sized to fill the body. The hardware keypad types
 * here too (hold 0 for +). The bar is "← Phonebook" until there's a number, then splits into
 * "← | Call | ⌫".
 */
@Composable
fun DialPadScreen(
    number: String,
    region: String,
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onPlus: () -> Unit,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val actions = if (number.isEmpty()) {
        listOf(SideAction.back("back", "Phonebook", onBack))
    } else {
        listOf(
            SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, motion = ActionMotion.Back),
            SideAction("call", "Call", SidekitIcon.Call, tone = ActionTone.Secondary, weight = 1.6f, motion = ActionMotion.Up, contentDescription = "Call $number") { onCall(number) },
            SideAction.square("delete", SidekitIcon.Undo, "Delete digit", onDelete, tone = ActionTone.Muted, motion = ActionMotion.Up),
        )
    }
    SidekitScaffold(
        title = "Dial",
        actions = actions,
        options = if (number.isEmpty()) emptyList() else listOf(SideAction("message", "Text this number", SidekitIcon.Chat) { onMessage(number) }),
    ) { padding ->
        val colors = SidekitTheme.colors
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .keypadDigits(onKey = onKey, onDelete = onDelete, onLongZero = onPlus),
            verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap),
        ) {
            SidekitCard(Modifier.fillMaxWidth()) {
                SidekitText(
                    if (number.isEmpty()) "Enter a number" else PhoneFormat.format(number, region),
                    style = SidekitTheme.typography.headline,
                    color = if (number.isEmpty()) colors.surfaceA6 else colors.light,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().align(Alignment.CenterEnd).testTag(PhonebookTags.DIAL_NUMBER),
                )
            }
            DIAL_KEYS.chunked(3).forEach { row ->
                SidekitItemRow(Modifier.weight(1f)) {
                    row.forEach { (digit, letters) -> DialKey(digit, letters, onKey, if (digit == '0') onPlus else null) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DialKey(digit: Char, letters: String, onKey: (Char) -> Unit, onLong: (() -> Unit)?) {
    val colors = SidekitTheme.colors
    // Not SidekitTile: that fixes the height at 104, and four rows of keys need ~72 here.
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(SidekitTheme.shapes.item)
            .background(colors.surfaceA3)
            .combinedClickable(role = Role.Button, onLongClick = onLong) { onKey(digit) }
            .semantics(mergeDescendants = true) { contentDescription = "Key $digit" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SidekitText(digit.toString(), style = SidekitTheme.typography.label)
        if (letters.isNotEmpty()) SidekitText(letters.uppercase(), style = SidekitTheme.typography.tiny, color = colors.surfaceA6)
    }
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier) {
    SidekitCard(modifier.fillMaxWidth(), color = ItemColour.Muted.container()) {
        SidekitText(text, style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
    }
}

/** "Missed · 14:02", "Outgoing · Tue", "Incoming · 3 Oct". */
object CallTime {
    fun describe(call: CallEntry, now: Long, is24h: Boolean): String {
        val kind = when (call.type) {
            CallType.Incoming -> "Incoming"
            CallType.Outgoing -> "Outgoing"
            CallType.Missed -> "Missed"
            CallType.Rejected -> "Declined"
            CallType.Blocked -> "Blocked"
            CallType.Other -> "Call"
        }
        return "$kind · ${whenText(call.date, now, is24h)}"
    }

    fun whenText(date: Long, now: Long, is24h: Boolean): String {
        val then = Calendar.getInstance().apply { timeInMillis = date }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay -> DateFormat.format(if (is24h) "HH:mm" else "h:mm a", then).toString()
            now - date < 6 * DateUtils.DAY_IN_MILLIS -> DateFormat.format("EEE", then).toString()
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> DateFormat.format("d MMM", then).toString()
            else -> DateFormat.format("d MMM yyyy", then).toString()
        }
    }
}

private object PhonebookSampleData {
    val contacts = listOf(
        Contact(1, "Amelie Hart", listOf(PhoneNumber("0412 345 678", "Mobile", primary = true)), starred = true),
        Contact(2, "Mum", listOf(PhoneNumber("0400 111 222", "Mobile"), PhoneNumber("02 9876 5432", "Home")), starred = true),
        Contact(3, "Ari Cohen", listOf(PhoneNumber("0433 222 111", "Mobile"))),
        Contact(4, "Fio Russo", listOf(PhoneNumber("0455 765 432", "Mobile"))),
        Contact(5, "Josie Bell", listOf(PhoneNumber("0466 888 999", "Work"))),
    )
}

@SidephonePreview
@Composable
private fun PhonebookHomePreview() = SidekitPreviewFrame {
    val all = PhonebookSampleData.contacts
    PhonebookHomeScreen(
        ContactsUiState(loaded = true, matches = T9Search.filter(all, ""), starred = all.filter { it.starred }),
        "AU", {}, {}, {}, {}, {}, {}, {}, {},
    )
}

@SidephonePreview
@Composable
private fun PhonebookSearchPreview() = SidekitPreviewFrame {
    PhonebookHomeScreen(ContactsUiState(loaded = true, query = "27", matches = T9Search.filter(PhonebookSampleData.contacts, "27")), "AU", {}, {}, {}, {}, {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun DialPreview() = SidekitPreviewFrame {
    DialPadScreen("0412345", "AU", {}, {}, {}, {}, {}, {})
}

@Composable
fun CallPermissionScreen(number: String, onBack: () -> Unit, onAllow: () -> Unit, onDialer: () -> Unit) {
    SidekitScaffold(
        title = "Call",
        actions = listOf(SideAction.back("back", "Back", onBack), SideAction("allow", "Allow", onClick = onAllow)),
        options = listOf(SideAction("dialer", "System dialer", onClick = onDialer)),
    ) { padding ->
        val state = rememberLazyListState()
        SidekitList(padding, state = state) {
            item { InfoCard("Allow phone access to call $number. You can also use the system dialer from Options.", Modifier.listFade(state, 0)) }
        }
    }
}
