package hrln.interactive.sidekit.apps.sidemessage

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.Telephony
import androidx.compose.runtime.Immutable
import hrln.interactive.sidekit.apps.phonebook.changes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

data class MmsAttachment(val uri: String, val mime: String, val name: String)

/** What the user can see about a sent message. */
enum class Delivery { None, Sending, Sent, Delivered, Failed }

/**
 * One message in a conversation. [key] is unique across SMS ("s<id>"), MMS ("m<id>") and messages
 * still being sent that the provider doesn't have yet ("p<token>").
 */
@Immutable
data class Message(
    val text: String,
    val fromMe: Boolean,
    val date: Long = 0L,
    val delivery: Delivery = Delivery.None,
    val key: String = "",
    /** The provider row this message lives in, or -1. [key] distinguishes SMS and MMS. */
    val rowId: Long = -1L,
    val address: String? = null,
    val attachments: List<MmsAttachment> = emptyList(),
    val pendingMms: String? = null,
)

/** A thread as the provider has it: addresses, no contact names yet. */
@Immutable
data class RawThread(val id: Long, val addresses: List<String>, val snippet: String, val date: Long, val unread: Boolean)

/** An inbox row. [name] is the contact name(s), or the number when there's no contact. */
@Immutable
data class ThreadSummary(val id: Long, val addresses: List<String>, val name: String, val snippet: String, val date: Long, val unread: Boolean)

/** Matching phone numbers across formats: "+61 412 345 678", "0412345678" and "412345678" are one person. */
object Addresses {
    /** A comparison key: the last 9 digits of a number, or the lower-cased text of an alphanumeric sender ("Telstra"). */
    fun key(address: String): String {
        val digits = address.filter(Char::isDigit)
        val hasLetters = address.any(Char::isLetter)
        return if (hasLetters || digits.length < 4) address.trim().lowercase() else digits.takeLast(9)
    }

    fun same(a: List<String>, b: List<String>): Boolean = a.map(::key).toSet() == b.map(::key).toSet()

    /** Splits the recipients of an `sms:`/`smsto:` URI ("0412,0413" or "0412;0413", maybe with "?body="). */
    fun fromUri(uri: Uri): List<String> {
        val part = uri.schemeSpecificPart.orEmpty().substringBefore('?')
        return part.split(',', ';').map { Uri.decode(it).trim() }.filter { it.isNotEmpty() }
    }
}

/** Where Sidemessage reads messages from. Blocking; called on IO only. */
interface MessageSource {
    fun threads(): List<RawThread>

    /** The newest [limit] messages of a thread, newest first. */
    fun messages(threadId: Long, limit: Int, before: Message? = null): List<Message>

    /** Marks a thread read. Only takes effect for the default SMS app. */
    fun markRead(threadId: Long)

    /** Emits when any message or thread changes. */
    fun changes(): Flow<Unit>
}

/**
 * The Telephony providers: threads from `content://mms-sms/conversations?simple=true` with
 * recipients resolved through `canonical-addresses`, SMS from [Telephony.Sms], and the text parts
 * of MMS from [Telephony.Mms] (pictures show as "[Picture]"; attachments can be viewed or opened after carrier download).
 */
class AndroidMessageSource(private val resolver: ContentResolver) : MessageSource {
    override fun threads(): List<RawThread> {
        val canonical = HashMap<Long, String>()
        query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canonical[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        val projection = arrayOf(
            Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.RECIPIENT_IDS,
            Telephony.Threads.SNIPPET, Telephony.Threads.READ, Telephony.Threads.MESSAGE_COUNT,
        )
        val out = ArrayList<RawThread>()
        query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < MAX_THREADS) {
                if (c.getInt(5) == 0) continue
                val addresses = c.getString(2).orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(canonical::get) }.filter { it.isNotBlank() }
                if (addresses.isEmpty()) continue
                out += RawThread(c.getLong(0), addresses, c.getString(3).orEmpty().trim(), c.getLong(1), unread = c.getInt(4) == 0)
            }
        }
        return out
    }

    override fun messages(threadId: Long, limit: Int, before: Message?): List<Message> {
        val sms = ArrayList<Message>()
        val smsProjection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE, Telephony.Sms.STATUS, Telephony.Sms.ADDRESS)
        queryNewest(Telephony.Sms.CONTENT_URI, smsProjection, "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.TYPE}!=${Telephony.Sms.MESSAGE_TYPE_DRAFT}" + beforeClause(before, "s", 1), arrayOf(threadId.toString()), Telephony.Sms.DATE, limit)?.use { c ->
            while (sms.size < limit && c.moveToNext()) {
                val type = c.getInt(2)
                sms += Message(
                    text = c.getString(1).orEmpty(),
                    fromMe = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    date = c.getLong(3),
                    delivery = smsDelivery(type, c.getInt(4)),
                    key = "s${c.getLong(0)}",
                    rowId = c.getLong(0),
                    address = c.getString(5),
                )
            }
        }
        val mms = mms(threadId, limit, before)
        if (mms.isEmpty()) return sms
        return (sms + mms).sortedWith(MESSAGE_ORDER).take(limit)
    }

    private fun mms(threadId: Long, limit: Int, before: Message?): List<Message> {
        val pendingIds = HashSet<Long>()
        val rows = ArrayList<Triple<Long, Long, Int>>() // id, date (ms), box
        queryNewest(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, "m_type"), "${Telephony.Mms.THREAD_ID}=?" + beforeClause(before, "m", 1000), arrayOf(threadId.toString()), Telephony.Mms.DATE, limit)?.use { c ->
            while (rows.size < limit && c.moveToNext()) { rows += Triple(c.getLong(0), c.getLong(1) * 1000L, c.getInt(2)); if (c.getInt(3) == 130) pendingIds += c.getLong(0) }
        }
        if (rows.isEmpty()) return emptyList()
        // All parts of these messages in one query, not one per message.
        val texts = HashMap<Long, StringBuilder>()
        val media = HashSet<Long>()
        val attachments = HashMap<Long, MutableList<MmsAttachment>>()
        val ids = rows.joinToString(",") { it.first.toString() }
        query(MMS_PART, arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, "_id", "cl"), "${Telephony.Mms.Part.MSG_ID} IN ($ids)", null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = c.getString(1).orEmpty()
                when {
                    type == "text/plain" -> texts.getOrPut(id) { StringBuilder() }.let { sb ->
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(c.getString(2).orEmpty())
                    }
                    type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/") -> {
                        media += id
                        attachments.getOrPut(id) { mutableListOf() }.add(MmsAttachment("content://mms/part/${c.getLong(3)}", type, c.getString(4) ?: "Attachment"))
                    }
                }
            }
        }
        return rows.map { (id, date, box) ->
            val text = texts[id]?.toString()?.trim().orEmpty()
            val body = when {
                id in media && text.isNotEmpty() -> "[Picture] $text"
                id in media -> "[Picture]"
                else -> text.ifEmpty { "[Message]" }
            }
            Message(
                text = if (id in pendingIds) "MMS pending · tap to download or retry" else body,
                fromMe = box != Telephony.Mms.MESSAGE_BOX_INBOX,
                date = date,
                delivery = when (box) {
                    Telephony.Mms.MESSAGE_BOX_OUTBOX -> Delivery.Sending
                    Telephony.Mms.MESSAGE_BOX_FAILED -> Delivery.Failed
                    Telephony.Mms.MESSAGE_BOX_SENT -> Delivery.Sent
                    else -> Delivery.None
                },
                key = "m$id",
                rowId = id, attachments = attachments[id].orEmpty(), pendingMms = if (id in pendingIds) "content://mms/$id" else null,
            )
        }
    }

    override fun markRead(threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        val where = "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0"
        val args = arrayOf(threadId.toString())
        try {
            resolver.update(Telephony.Sms.CONTENT_URI, values, where, args)
            resolver.update(Telephony.Mms.CONTENT_URI, values, where, args)
        } catch (_: RuntimeException) {
            // Not the default SMS app (writes are refused), or the provider is unavailable.
        }
    }

    override fun changes(): Flow<Unit> = merge(resolver.changes(Telephony.Sms.CONTENT_URI), resolver.changes(Telephony.MmsSms.CONTENT_URI))

    private fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor? = try {
        resolver.query(uri, projection, selection, args, sort)
    } catch (_: SecurityException) {
        null
    } catch (_: SQLiteException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Newest [limit] rows. Asks the provider for a LIMIT so only one page leaves its process; if a
     * provider rejects that, the full ordered cursor is read only as far as [limit] (its window
     * holds at most a couple of MB, so memory stays bounded either way).
     */
    private fun queryNewest(uri: Uri, projection: Array<String>, selection: String, args: Array<String>, dateColumn: String, limit: Int): Cursor? {
        if (limitSupported != false) {
            try {
                val c = resolver.query(uri, projection, selection, args, "$dateColumn DESC, _id DESC LIMIT $limit")
                limitSupported = true
                return c
            } catch (_: SecurityException) {
                return null
            } catch (_: RuntimeException) {
                limitSupported = false
            }
        }
        return query(uri, projection, selection, args, "$dateColumn DESC, _id DESC")
    }

    private fun beforeClause(before: Message?, kind: String, scale: Int): String {
        before ?: return ""
        val date = before.date
        val sameDate = when {
            before.key.startsWith(kind) -> "_id < ${before.key.drop(1).toLong()}"
            kind < before.key.take(1) -> "1=1"
            else -> "1=0"
        }
        return " AND (date * $scale < $date OR (date * $scale = $date AND $sameDate))"
    }

    @Volatile private var limitSupported: Boolean? = null

    companion object {
        const val MAX_THREADS = 300
        private val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private val MMS_PART: Uri = Uri.parse("content://mms/part")

        fun smsDelivery(type: Int, status: Int): Delivery = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> Delivery.None
            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> Delivery.Sending
            Telephony.Sms.MESSAGE_TYPE_FAILED -> Delivery.Failed
            else -> when (status) {
                Telephony.Sms.STATUS_COMPLETE -> Delivery.Delivered
                Telephony.Sms.STATUS_FAILED -> Delivery.Failed
                else -> Delivery.Sent
            }
        }
    }
}

internal val MESSAGE_ORDER = compareByDescending<Message> { it.date }.thenByDescending { it.key.take(1) }.thenByDescending { it.key.drop(1).toLongOrNull() ?: 0L }
