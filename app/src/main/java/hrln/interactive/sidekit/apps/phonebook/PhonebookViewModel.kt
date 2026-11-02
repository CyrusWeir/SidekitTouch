package hrln.interactive.sidekit.apps.phonebook

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Loads contacts while collected, and again whenever they change. Null until the first load. */
@OptIn(ExperimentalCoroutinesApi::class)
fun PhonebookSource.liveContacts(allowed: Flow<Boolean>, io: CoroutineDispatcher = Dispatchers.IO): Flow<List<Contact>?> =
    allowed.flatMapLatest { ok ->
        if (!ok) flowOf(emptyList())
        else contactChanges().onStart { emit(Unit) }.conflate().map { contacts() }.flowOn(io)
    }

data class ContactsUiState(
    val allowed: Boolean = true,
    val loaded: Boolean = false,
    /** Keypad digits typed on the contacts list. */
    val query: String = "",
    /** All contacts, or those matching [query]. */
    val matches: List<ContactMatch> = emptyList(),
    /** Starred contacts, shown as tiles while not searching. */
    val starred: List<Contact> = emptyList(),
)

data class RecentsUiState(val allowed: Boolean = true, val loaded: Boolean = false, val calls: List<CallRow> = emptyList())

/** A call log row with its contact name resolved (or null for an unknown number). */
data class CallRow(val call: CallEntry, val name: String?, val contactId: Long?)

class PhonebookViewModel(
    private val source: PhonebookSource,
    io: CoroutineDispatcher = Dispatchers.IO,
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val contactsAllowed = MutableStateFlow(false)
    private val callsAllowed = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val _dial = MutableStateFlow("")

    /** The number on the dial pad. */
    val dial: StateFlow<String> = _dial.asStateFlow()

    /** Every contact with a number, alphabetical; null until loaded. */
    val contacts: StateFlow<List<Contact>?> = source.liveContacts(contactsAllowed, io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val contactsState: StateFlow<ContactsUiState> = combine(contacts, query, contactsAllowed) { list, q, allowed ->
        val all = list.orEmpty()
        ContactsUiState(
            allowed = allowed,
            loaded = list != null,
            query = q,
            matches = T9Search.filter(all, q),
            starred = if (q.isEmpty()) all.filter { it.starred }.take(MAX_STARRED) else emptyList(),
        )
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ContactsUiState(allowed = false))

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentsState: StateFlow<RecentsUiState> = callsAllowed.flatMapLatest { ok ->
        if (!ok) flowOf(RecentsUiState(allowed = false, loaded = true))
        else combine(
            source.callChanges().onStart { emit(Unit) }.conflate().map { source.calls(MAX_CALLS) }.flowOn(io),
            contacts,
        ) { calls, list -> RecentsUiState(true, true, calls.map { row(it, list.orEmpty()) }) }.flowOn(compute)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), RecentsUiState(loaded = false))

    fun setContactsAccess(granted: Boolean) { contactsAllowed.value = granted }
    fun setCallLogAccess(granted: Boolean) { callsAllowed.value = granted }

    fun contact(id: Long): Contact? = contacts.value?.firstOrNull { it.id == id }

    /** The contact a number belongs to, if any. */
    fun contactFor(number: String): Contact? = contacts.value.orEmpty().let { findContact(it, number) }

    // Contacts search (hardware keypad digits).
    fun typeDigit(d: Char) = query.update { if (it.length >= MAX_DIGITS) it else it + d }
    fun deleteDigit() = query.update { it.dropLast(1) }
    fun clearQuery() { query.value = "" }

    // Dial pad.
    fun setDial(number: String) { _dial.value = number.take(MAX_DIGITS) }
    fun dialKey(c: Char) = _dial.update { if (it.length >= MAX_DIGITS) it else it + c }
    fun dialDelete() = _dial.update { it.dropLast(1) }

    /** Long-press 0: the "+" of an international number replaces the 0 just typed. */
    fun dialPlus() = _dial.update { if (it.endsWith('0')) it.dropLast(1) + '+' else it + '+' }

    private fun row(call: CallEntry, contacts: List<Contact>): CallRow {
        val contact = findContact(contacts, call.number)
        return CallRow(call, contact?.name ?: call.cachedName, contact?.id)
    }

    companion object {
        const val MAX_DIGITS = 32
        const val MAX_STARRED = 4
        const val MAX_CALLS = 100

        /** Last-7-digits match, as the platform's caller ID does: tolerant of +61 vs 0 prefixes. */
        fun findContact(contacts: List<Contact>, number: String): Contact? {
            val digits = number.filter(Char::isDigit)
            if (digits.length < 3) return null
            val tail = digits.takeLast(7)
            return contacts.firstOrNull { c -> c.numbers.any { it.digits == digits || (tail.length == 7 && it.digits.endsWith(tail)) } }
        }
    }
}

/** Process-wide Phonebook data access (the providers are the store; nothing is copied to disk). */
object Phonebook {
    @Volatile private var names: ContactNames? = null
    @Volatile var sourceOverride: PhonebookSource? = null

    fun source(context: Context): PhonebookSource = sourceOverride ?: AndroidPhonebookSource(context.applicationContext.contentResolver)

    /** Shared number -> name cache (Sidemessage threads, notifications). */
    fun names(context: Context): ContactNames = names ?: synchronized(this) {
        names ?: ContactNames.android(context.applicationContext.contentResolver).also { names = it }
    }
}
