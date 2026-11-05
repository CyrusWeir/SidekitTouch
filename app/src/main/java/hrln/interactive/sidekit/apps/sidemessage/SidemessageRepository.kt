package hrln.interactive.sidekit.apps.sidemessage

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.core.util.AtomicFile
import hrln.interactive.sidekit.apps.phonebook.ContactNames
import hrln.interactive.sidekit.apps.phonebook.Phonebook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** A message handed to the radio that the provider doesn't show yet (Sidekit is not the default SMS app). */
@Immutable
data class Outgoing(val token: Long, val address: String, val text: String, val date: Long, val delivery: Delivery)

/** A message waiting out its undo window before its provider row is really removed. */
data class PendingDelete(val message: Message, val token: Long)

/** Pinned addresses, one per line. Called off the main thread only. */
interface PinStore {
    fun read(): List<String>
    fun write(pins: List<String>)
}

/** Pins in a small text file replaced atomically, as Sidenotes stores notes. */
class FilePinStore(file: File) : PinStore {
    private val file = AtomicFile(file)

    override fun read(): List<String> = try {
        file.readFully().toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: IOException) {
        emptyList()
    }

    override fun write(pins: List<String>) {
        val out = file.startWrite()
        try {
            out.write(pins.joinToString("\n").toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }
}

/**
 * Sidemessage's data, shared by its screens and its receivers.
 *
 * Messages themselves live in the Telephony provider; this holds only what the provider can't:
 * pinned contacts (persisted) and, when Sidekit isn't the default SMS app, the delivery state of
 * messages it sent (in memory, since only the default app may write that to the provider).
 */
class SidemessageRepository(
    val source: MessageSource,
    private val transport: SmsTransport,
    private val pinStore: PinStore,
    val names: ContactNames,
    private val contactChanges: () -> Flow<Unit>,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _pins = MutableStateFlow<List<String>?>(null)

    /** Pinned addresses in pin order. Null until read. */
    val pins: StateFlow<List<String>?> = _pins.asStateFlow()

    private val _outgoing = MutableStateFlow<List<Outgoing>>(emptyList())
    val outgoing: StateFlow<List<Outgoing>> = _outgoing.asStateFlow()

    private val _pendingKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Message keys hidden from a conversation while their deletion can still be undone. */
    val pendingKeys: StateFlow<Set<String>> = _pendingKeys.asStateFlow()

    private val _deleted = MutableStateFlow<PendingDelete?>(null)

    /** The most recent deletion the user can still undo, or null. */
    val deleted: StateFlow<PendingDelete?> = _deleted.asStateFlow()

    private var undoJob: Job? = null
    private val deleteTokens = AtomicLong(0)

    private val tokens = AtomicLong(clock())

    /** Address keys of the conversation on screen; its incoming messages don't notify. */
    @Volatile var visible: Set<String>? = null

    val isDefault: Boolean get() = transport.isDefault

    init {
        scope.launch(io) {
            val loaded = try {
                pinStore.read()
            } catch (_: IOException) {
                emptyList()
            }
            var written: List<String>? = null
            _pins.update { current -> current ?: loaded.also { written = it } }
            _pins.filterNotNull().conflate().collect { pins ->
                if (pins !== written) {
                    try {
                        pinStore.write(pins)
                        written = pins
                    } catch (_: IOException) {
                        // Keep the in-memory copy; the next change retries.
                    }
                }
            }
        }
    }

    fun isPinned(addresses: List<String>) = addresses.size == 1 && _pins.value.orEmpty().any { Addresses.key(it) == Addresses.key(addresses[0]) }

    fun pin(address: String) = _pins.update { pins ->
        val list = pins.orEmpty()
        if (list.any { Addresses.key(it) == Addresses.key(address) }) list else (list + address).takeLast(MAX_PINS)
    }

    fun unpin(address: String) = _pins.update { pins -> pins.orEmpty().filterNot { Addresses.key(it) == Addresses.key(address) } }

    /** The inbox, reloaded whenever messages or contacts change, while collected. */
    fun threads(): Flow<List<ThreadSummary>> =
        merge(source.changes(), contactChanges().onEach { names.clear() })
            .onStart { names.clear(); emit(Unit) }
            .conflate()
            .map { source.threads().map(::summarize) }
            .flowOn(io)

    fun summarize(raw: RawThread) = ThreadSummary(raw.id, raw.addresses, displayName(raw.addresses), raw.snippet, raw.date, raw.unread)

    fun displayName(addresses: List<String>): String = addresses.joinToString(", ") { names.name(it) ?: it }

    /** The thread with exactly these recipients, if there is one yet. Blocking. */
    fun findThread(addresses: List<String>): Long? = source.threads().firstOrNull { Addresses.same(it.addresses, addresses) }?.id

    /** Sends [text] to each address (a group thread is sent as individual SMS). */
    fun send(addresses: List<String>, text: String, threadId: Long): kotlinx.coroutines.Job? {
        val body = text.trim()
        if (body.isEmpty()) return null
        return scope.launch(io) { addresses.forEach { sendOne(it, body, threadId) } }
    }

    /** Sends a failed message again, replacing it. */
    fun retry(message: Message, addresses: List<String>, threadId: Long) {
        if (!message.fromMe || message.delivery != Delivery.Failed) return
        scope.launch(io) {
            if (message.rowId >= 0 && transport.isDefault) transport.deleteRow(message.rowId)
            if (message.key.startsWith("p")) {
                val token = message.key.drop(1).toLongOrNull()
                _outgoing.update { list -> list.filterNot { it.token == token } }
            }
            (message.address?.let { listOf(it) } ?: addresses).forEach { sendOne(it, message.text, threadId) }
        }
    }

    /**
     * Removes [message] from the conversation at once, but waits [UNDO_WINDOW_MS] before touching
     * the provider so [undoDelete] can put it back. Pending outgoing messages are local only.
     */
    fun delete(message: Message) {
        commitPending()
        val token = deleteTokens.incrementAndGet()
        _pendingKeys.update { it + message.key }
        _deleted.value = PendingDelete(message, token)
        undoJob = scope.launch(io) {
            delay(UNDO_WINDOW_MS)
            val pending = _deleted.value?.takeIf { it.token == token } ?: return@launch
            _deleted.value = null
            finishDelete(pending.message)
        }
    }

    /** Puts back the message waiting out its window, if any. */
    fun undoDelete() {
        val pending = _deleted.value ?: return
        undoJob?.cancel()
        _deleted.value = null
        _pendingKeys.update { it - pending.message.key }
    }

    /** Commits the deletion still on offer when a new one starts. */
    private fun commitPending() {
        val pending = _deleted.value ?: return
        _deleted.value = null
        scope.launch(io) { finishDelete(pending.message) }
    }

    /**
     * Removes [message] for good. A provider row that is really gone stays hidden: no reload can
     * bring it back, so the stale page can't flash it in while it waits for the next load.
     */
    private fun finishDelete(message: Message) {
        val removed = performDelete(message)
        // A pending outgoing message is local, and a failed delete leaves its row in place.
        if (message.key.startsWith("p") || !removed) _pendingKeys.update { it - message.key }
    }

    private fun performDelete(message: Message): Boolean {
        if (message.key.startsWith("p")) {
            val token = message.key.drop(1).toLongOrNull()
            _outgoing.update { list -> list.filterNot { it.token == token } }
            return true
        }
        return transport.isDefault && transport.delete(message)
    }

    private fun sendOne(address: String, text: String, threadId: Long) {
        val token = tokens.incrementAndGet()
        val now = clock()
        val row = if (transport.isDefault) transport.insertOutgoing(address, text, now, threadId) else null
        if (row == null) _outgoing.update { (it + Outgoing(token, address, text, now, Delivery.Sending)).takeLast(MAX_OUTGOING) }
        if (!transport.send(address, text, token, row ?: -1L)) apply(token, row ?: -1L, Delivery.Failed)
    }

    fun report(token: Long, rowId: Long, delivery: Delivery) = apply(token, rowId, delivery)

    private fun apply(token: Long, rowId: Long, delivery: Delivery) {
        if (rowId >= 0) transport.markRow(rowId, delivery)
        _outgoing.update { list ->
            list.map { if (it.token == token && it.delivery != Delivery.Failed) it.copy(delivery = delivery) else it }
        }
    }

    fun isVisible(address: String) = visible?.contains(Addresses.key(address)) == true

    companion object {
        const val MAX_PINS = 4
        const val MAX_OUTGOING = 50

        /** How long a deleted message can be restored from the action bar. */
        const val UNDO_WINDOW_MS = 5_000L

        /**
         * Shows [outgoing] messages to [addresses] in a conversation. A message the provider already
         * has (same text, sent within two minutes) shows once, with the better-known state: the
         * provider knows sent/failed, this app may know "delivered" when it isn't the default.
         * [messages] and the result are newest first.
         */
        fun mergeOutgoing(messages: List<Message>, outgoing: List<Outgoing>, addresses: List<String>): List<Message> {
            val keys = addresses.map(Addresses::key).toSet()
            val mine = outgoing.filter { Addresses.key(it.address) in keys }
            if (mine.isEmpty()) return messages
            val unmatched = mine.toMutableList()
            val merged = messages.map { m ->
                if (!m.fromMe) return@map m
                val o = unmatched.firstOrNull { it.text == m.text && abs(it.date - m.date) < MATCH_WINDOW_MS } ?: return@map m
                unmatched.remove(o)
                if (m.delivery == Delivery.Sent && o.delivery == Delivery.Delivered) m.copy(delivery = Delivery.Delivered) else m
            }
            if (unmatched.isEmpty()) return merged
            val pending = unmatched.map { Message(it.text, fromMe = true, date = it.date, delivery = it.delivery, key = "p${it.token}", address = it.address) }
            return (merged + pending).sortedByDescending { it.date }
        }

        private const val MATCH_WINDOW_MS = 120_000L
    }
}

/** Process-wide [SidemessageRepository], created on first use (by a screen or a receiver). */
object Sidemessage {
    @Volatile private var repository: SidemessageRepository? = null

    fun repository(context: Context): SidemessageRepository = repository ?: synchronized(this) {
        repository ?: run {
            val app = context.applicationContext
            SidemessageRepository(
                source = AndroidMessageSource(app.contentResolver),
                transport = AndroidSmsTransport(app),
                pinStore = FilePinStore(File(app.filesDir, "sidemessage_pins.txt")),
                names = Phonebook.names(app),
                contactChanges = { Phonebook.source(app).contactChanges() },
                scope = CoroutineScope(SupervisorJob()),
            )
        }.also { repository = it }
    }

    @VisibleForTesting
    fun install(repo: SidemessageRepository?) {
        repository = repo
    }
}
