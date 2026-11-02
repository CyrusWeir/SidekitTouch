package hrln.interactive.sidekit.apps.phonebook

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.LruCache
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.text.Normalizer

/** One phone number of a contact. [digits] are the number's digits only (for keypad search). */
@Immutable
data class PhoneNumber(
    val number: String,
    val label: String,
    val normalized: String? = null,
    val primary: Boolean = false,
) {
    val digits: String = number.filter(Char::isDigit)
    private val normalizedDigits: String = normalized?.filter(Char::isDigit).orEmpty()

    fun containsDigits(query: String) = digits.contains(query) || normalizedDigits.contains(query)
}

/**
 * A contact with at least one phone number. The T9 keys of the name are worked out once here,
 * when contacts load, not on every keypress.
 */
@Immutable
class Contact(
    val id: Long,
    val name: String,
    val numbers: List<PhoneNumber>,
    val starred: Boolean = false,
) {
    /** T9 digits of each word of the name ("Ann Lee" -> ["266", "533"]). */
    val wordKeys: List<String> = T9Search.words(name)

    /** T9 digits of the whole name without spaces ("Ann Lee" -> "266533"). */
    val fullKey: String = wordKeys.joinToString("")

    /** The number to call or text by default: the super-primary one, else the first. */
    val primaryNumber: PhoneNumber get() = numbers.firstOrNull { it.primary } ?: numbers.first()

    override fun equals(other: Any?) =
        other is Contact && other.id == id && other.name == name && other.numbers == numbers && other.starred == starred

    override fun hashCode() = id.hashCode()
    override fun toString() = "Contact($id, $name)"
}

enum class CallType { Incoming, Outgoing, Missed, Rejected, Blocked, Other }

@Immutable
data class CallEntry(
    val id: Long,
    val number: String,
    val cachedName: String?,
    val type: CallType,
    val date: Long,
    val durationSec: Long,
)

/** How a contact matched a keypad search. Lower sorts first. */
enum class MatchKind { NamePrefix, Number }

@Immutable
data class ContactMatch(val contact: Contact, val kind: MatchKind, val number: PhoneNumber?)

/**
 * Nokia-style keypad search: typing digits finds contacts whose name spells those keys (any
 * word, or the whole name run together) *and* contacts whose number contains those digits.
 */
object T9Search {
    private const val KEYS = "22233344455566677778889999"

    /** The keypad digit for a letter or digit, or null for anything else (spaces, punctuation). */
    fun key(c: Char): Char? = when (c) {
        in '0'..'9' -> c
        in 'a'..'z' -> KEYS[c - 'a']
        in 'A'..'Z' -> KEYS[c - 'A']
        else -> null
    }

    /** Strips accents ("Zoë" -> "Zoe") so they map to their base letter's key. */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    fun words(name: String): List<String> =
        fold(name).split(' ', '-', '.', ',', '\'', '(', ')').mapNotNull { w ->
            w.mapNotNull(::key).joinToString("").ifEmpty { null }
        }

    fun match(contact: Contact, query: String): ContactMatch? {
        if (query.isEmpty()) return ContactMatch(contact, MatchKind.NamePrefix, null)
        if (contact.wordKeys.any { it.startsWith(query) } || contact.fullKey.startsWith(query)) {
            return ContactMatch(contact, MatchKind.NamePrefix, null)
        }
        val number = contact.numbers.firstOrNull { it.containsDigits(query) } ?: return null
        return ContactMatch(contact, MatchKind.Number, number)
    }

    /** Matches in display order: name matches first (alphabetical, as given), then number matches. */
    fun filter(contacts: List<Contact>, query: String): List<ContactMatch> {
        val digits = query.filter { it in '0'..'9' }
        if (digits.isEmpty()) return contacts.map { ContactMatch(it, MatchKind.NamePrefix, null) }
        val names = ArrayList<ContactMatch>()
        val numbers = ArrayList<ContactMatch>()
        for (c in contacts) {
            val m = match(c, digits) ?: continue
            if (m.kind == MatchKind.NamePrefix) names += m else numbers += m
        }
        return names + numbers
    }

    /** Plain-text search (New Message "To:" field): name contains the text, or number contains its digits. */
    fun filterText(contacts: List<Contact>, text: String): List<ContactMatch> {
        val t = fold(text.trim()).lowercase()
        if (t.isEmpty()) return contacts.map { ContactMatch(it, MatchKind.NamePrefix, null) }
        val digits = t.filter(Char::isDigit)
        val looksLikeNumber = digits.isNotEmpty() && t.all { it.isDigit() || it in "+ -()" }
        val out = ArrayList<ContactMatch>()
        for (c in contacts) {
            if (!looksLikeNumber && fold(c.name).lowercase().contains(t)) {
                out += ContactMatch(c, MatchKind.NamePrefix, null)
            } else if (looksLikeNumber) {
                c.numbers.firstOrNull { it.containsDigits(digits) }?.let { out += ContactMatch(c, MatchKind.Number, it) }
            }
        }
        return out
    }
}

/** Where Phonebook reads from. Blocking; called on IO only. */
interface PhonebookSource {
    fun contacts(): List<Contact>
    fun calls(limit: Int): List<CallEntry>
    fun contactChanges(): Flow<Unit>
    fun callChanges(): Flow<Unit>
}

/** [ContactsContract] and [CallLog] through the [ContentResolver]. */
class AndroidPhonebookSource(private val resolver: ContentResolver) : PhonebookSource {
    override fun contacts(): List<Contact> {
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.NORMALIZED_NUMBER,
            Phone.TYPE, Phone.LABEL, Phone.STARRED, Phone.IS_SUPER_PRIMARY,
        )
        val cursor = try {
            resolver.query(Phone.CONTENT_URI, projection, null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")
        } catch (_: SecurityException) {
            null
        } ?: return emptyList()
        // One row per number; fold them into contacts, keeping the provider's alphabetical order.
        val byId = LinkedHashMap<Long, Triple<String, Boolean, MutableList<PhoneNumber>>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val label = typeLabel(c.getInt(4), c.getString(5))
                val entry = byId.getOrPut(id) { Triple(name, c.getInt(6) != 0, ArrayList()) }
                val phone = PhoneNumber(number, label, c.getString(3), primary = c.getInt(7) != 0)
                // The same number is often stored twice (SIM import, account sync): show it once.
                if (entry.third.none { it.digits == phone.digits }) entry.third += phone
            }
        }
        return byId.map { (id, e) -> Contact(id, e.first, e.third, e.second) }
    }

    override fun calls(limit: Int): List<CallEntry> {
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val cursor = try {
            resolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyList()
        val out = ArrayList<CallEntry>(limit)
        cursor.use { c ->
            while (out.size < limit && c.moveToNext()) {
                out += CallEntry(
                    id = c.getLong(0),
                    number = c.getString(1).orEmpty(),
                    cachedName = c.getString(2)?.takeIf { it.isNotBlank() },
                    type = when (c.getInt(3)) {
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.Incoming
                        CallLog.Calls.OUTGOING_TYPE -> CallType.Outgoing
                        CallLog.Calls.MISSED_TYPE -> CallType.Missed
                        CallLog.Calls.REJECTED_TYPE -> CallType.Rejected
                        CallLog.Calls.BLOCKED_TYPE -> CallType.Blocked
                        else -> CallType.Other
                    },
                    date = c.getLong(4),
                    durationSec = c.getLong(5),
                )
            }
        }
        return out
    }

    override fun contactChanges(): Flow<Unit> = resolver.changes(ContactsContract.Contacts.CONTENT_URI)
    override fun callChanges(): Flow<Unit> = resolver.changes(CallLog.Calls.CONTENT_URI)

    private fun typeLabel(type: Int, custom: String?): String = when (type) {
        Phone.TYPE_MOBILE -> "Mobile"
        Phone.TYPE_HOME -> "Home"
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "Work"
        Phone.TYPE_MAIN -> "Main"
        Phone.TYPE_CUSTOM -> custom?.takeIf { it.isNotBlank() } ?: "Phone"
        else -> "Phone"
    }
}

/**
 * Emits whenever [uri] (or anything under it) changes. The observer is registered only while the
 * flow is collected, so a screen that isn't visible watches nothing. Bursts (a sync touching 200
 * contacts) collapse into one pending emission.
 */
fun ContentResolver.changes(uri: Uri): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    val registered = try {
        registerContentObserver(uri, true, observer)
        true
    } catch (_: SecurityException) {
        false
    }
    awaitClose { if (registered) unregisterContentObserver(observer) }
}.buffer(1, BufferOverflow.DROP_OLDEST)

/**
 * Contact names for phone numbers (Sidemessage threads, call log rows), cached. A miss costs a
 * [ContactsContract.PhoneLookup] query, so the cache keeps the last few hundred answers, including
 * "no contact". [clear] when contacts change.
 */
class ContactNames(private val lookup: (String) -> String?) {
    private val cache = LruCache<String, Any>(256)

    fun name(address: String): String? {
        if (address.isBlank()) return null
        when (val hit = cache.get(address)) {
            NONE -> return null
            is String -> return hit
        }
        val name = lookup(address)
        cache.put(address, name ?: NONE)
        return name
    }

    fun clear() = cache.evictAll()

    private object NONE

    companion object {
        fun android(resolver: ContentResolver) = ContactNames { address ->
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            try {
                resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
