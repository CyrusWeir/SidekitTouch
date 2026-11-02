package hrln.interactive.sidekit.apps.sidemessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hrln.interactive.sidekit.apps.phonebook.ContactMatch
import hrln.interactive.sidekit.apps.phonebook.PhonebookSource
import hrln.interactive.sidekit.apps.phonebook.T9Search
import hrln.interactive.sidekit.apps.phonebook.liveContacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Sidemessage may do: read the SMS provider (READ_SMS), and write it (the SMS role). */
data class SmsAccess(val canRead: Boolean = false, val isDefault: Boolean = false)

/** A pinned contact's tile. [thread] is null until there's a conversation with them. */
data class PinnedTile(val address: String, val name: String, val thread: ThreadSummary?)

data class InboxUiState(
    val access: SmsAccess = SmsAccess(),
    val loaded: Boolean = false,
    val pinned: List<PinnedTile> = emptyList(),
    /** Conversations not pinned to the top, newest first. */
    val threads: List<ThreadSummary> = emptyList(),
)

data class NewMessageUiState(
    val contactsAllowed: Boolean = true,
    val query: String = "",
    /** The query as a phone number, if it is one. */
    val number: String? = null,
    val matches: List<ContactMatch> = emptyList(),
)

/** Inbox and New Message: one per visit to Sidemessage, shared by its screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class SidemessageViewModel(
    private val repo: SidemessageRepository,
    contacts: PhonebookSource,
    io: CoroutineDispatcher = Dispatchers.IO,
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val access = MutableStateFlow(SmsAccess())
    private val contactsAllowed = MutableStateFlow(false)
    private val query = MutableStateFlow("")

    private val threads: Flow<List<ThreadSummary>?> = combine(access.map { it.canRead }, contactsAllowed) { read, contacts -> read to contacts }.distinctUntilChanged().flatMapLatest { (ok, _) ->
        if (ok) repo.threads() else flowOf(emptyList())
    }

    val inbox: StateFlow<InboxUiState> = combine(threads, repo.pins, access) { list, pins, acc ->
        val all = list.orEmpty()
        val tiles = pins.orEmpty().map { address ->
            val thread = all.firstOrNull { t -> t.addresses.size == 1 && Addresses.key(t.addresses[0]) == Addresses.key(address) }
            PinnedTile(address, thread?.name ?: repo.displayName(listOf(address)), thread)
        }
        val pinnedIds = tiles.mapNotNull { it.thread?.id }.toSet()
        InboxUiState(acc, list != null && pins != null, tiles, all.filterNot { it.id in pinnedIds })
    }.flowOn(io).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), InboxUiState())

    val newMessage: StateFlow<NewMessageUiState> = combine(contacts.liveContacts(contactsAllowed, io), query, contactsAllowed) { list, q, allowed ->
        NewMessageUiState(allowed, q, numberFrom(q), T9Search.filterText(list.orEmpty(), q).take(MAX_MATCHES))
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NewMessageUiState())

    fun setAccess(canRead: Boolean, isDefault: Boolean) { access.value = SmsAccess(canRead, isDefault) }
    fun setContactsAccess(granted: Boolean) { contactsAllowed.value = granted }
    fun setQuery(text: String) { query.value = text }

    fun pin(address: String) = repo.pin(address)
    fun unpin(address: String) = repo.unpin(address)

    companion object {
        const val MAX_MATCHES = 50
        private val NUMBER = Regex("""^\+?[0-9][0-9 ()\-]{2,}$""")

        /** "0412 345 678" -> "0412345678"; "+61 412" -> "+61412"; names -> null. */
        fun numberFrom(text: String): String? {
            val t = text.trim()
            if (!NUMBER.matches(t)) return null
            val digits = t.filter(Char::isDigit)
            return if (t.startsWith('+')) "+$digits" else digits
        }
    }
}

data class ConversationUiState(
    val title: String,
    val access: SmsAccess = SmsAccess(),
    val loaded: Boolean = false,
    /** Newest first. */
    val messages: List<Message> = emptyList(),
    /** More, older messages can be paged in. */
    val hasMore: Boolean = false,
    val hasNewer: Boolean = false,
    /** Message being typed ("text - typing", 35:268), or null when not typing. */
    val draft: String? = null,
    val pinned: Boolean = false,
)

private data class Page(val messages: List<Message>, val hasMore: Boolean, val loaded: Boolean = true, val hasNewer: Boolean = false)
private data class Window(val limit: Int, val before: Message? = null)

/**
 * One conversation. Pages the thread newest first: [PAGE] messages at a time, more as the user
 * scrolls back, never more than [MAX_LOADED] in memory. Reloads while on screen whenever the
 * provider changes (a message arrives, a sent report lands).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModel(
    private val repo: SidemessageRepository,
    val addresses: List<String>,
    threadId: Long,
    initialDraft: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val access = MutableStateFlow(SmsAccess())
    private val window = MutableStateFlow(Window(PAGE))
    private var oldest: Message? = null
    private var markedSnapshot: Pair<Long, List<String>>? = null
    private val thread = MutableStateFlow(threadId)
    private val draft = MutableStateFlow(initialDraft?.takeIf { it.isNotBlank() })
    private val title = MutableStateFlow(addresses.joinToString(", "))

    /** The thread id, once known (a new conversation has none until its first message). */
    val threadId: StateFlow<Long> get() = thread

    init {
        viewModelScope.launch(io) { title.value = repo.displayName(addresses) }
    }

    private val page: Flow<Page> = access.flatMapLatest { acc ->
        if (!acc.canRead) flowOf(Page(emptyList(), hasMore = false))
        else combine(repo.source.changes().onStart { emit(Unit) }.conflate(), window) { _, n -> n }
            .mapLatest { n -> load(n, acc.isDefault) }
            .flowOn(io)
    }

    private fun load(window: Window, isDefault: Boolean): Page {
        var id = thread.value
        if (id <= 0) id = repo.findThread(addresses) ?: -1L
        thread.value = id
        if (id <= 0) return Page(emptyList(), hasMore = false)
        val messages = repo.source.messages(id, window.limit + 1, window.before)
        val shown = messages.take(window.limit)
        oldest = shown.lastOrNull()
        // Only the default SMS app can mark messages read; for others this is a no-op.
        val snapshot = id to shown.map { it.key }
        if (isDefault && markedSnapshot != snapshot) {
            markedSnapshot = snapshot
            repo.source.markRead(id)
        } else if (!isDefault) markedSnapshot = null
        return Page(shown, hasMore = messages.size > window.limit, hasNewer = window.before != null)
    }

    private val messages: Flow<Pair<Page, List<Message>>> = combine(page, repo.outgoing) { p, out ->
        p to if (p.hasNewer) p.messages else SidemessageRepository.mergeOutgoing(p.messages, out, addresses)
    }

    val state: StateFlow<ConversationUiState> = combine(messages, draft, repo.pins, title, access) { (p, list), d, _, t, acc ->
        ConversationUiState(t, acc, p.loaded, list, p.hasMore, p.hasNewer, d, repo.isPinned(addresses))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ConversationUiState(addresses.joinToString(", ")))

    fun setAccess(canRead: Boolean, isDefault: Boolean) { access.value = SmsAccess(canRead, isDefault) }

    /** Pages in older messages (called as the user scrolls back towards the start). */
    fun loadOlder() = window.update {
        if (it.limit < MAX_LOADED) it.copy(limit = (it.limit + PAGE).coerceAtMost(MAX_LOADED))
        else Window(PAGE, oldest ?: it.before)
    }

    fun loadLatest() { window.value = Window(PAGE) }

    fun startTyping() = draft.update { it ?: "" }
    fun updateDraft(text: String) = draft.update { if (it == null) it else text }
    fun stopTyping() { draft.value = null }

    /** Sends the draft unless blank, and closes the compose box. */
    fun send() {
        val text = draft.value?.trim().orEmpty()
        draft.value = null
        if (text.isNotEmpty()) repo.send(addresses, text, thread.value)
    }

    fun retry(message: Message) = repo.retry(message, addresses, thread.value)

    fun togglePin() {
        val address = addresses.singleOrNull() ?: return
        if (repo.isPinned(addresses)) repo.unpin(address) else repo.pin(address)
    }

    companion object {
        const val PAGE = 50
        const val MAX_LOADED = 1_000
    }
}
