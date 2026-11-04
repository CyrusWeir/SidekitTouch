package hrln.interactive.sidekit.apps.mail

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Local folders and the latest IMAP inbox snapshot. */
enum class MailFolder(val label: String) { Draft("Drafts"), Outbox("Outbox"), HandedOff("Handed off"), Inbox("Inbox"), Sent("Sent") }

enum class MailSendResult { HandedOff, Unavailable, Sent }

/** Mail account transport; successful SMTP submission is distinct from delivery. */
interface MailAccount {
    val configured: Boolean
    suspend fun send(message: MailMessage): MailSendResult
    suspend fun inbox(): List<MailMessage> = emptyList()
}

interface MailStore {
    fun read(): List<MailMessage>
    fun write(messages: List<MailMessage>)
}

class FileMailStore(file: File) : MailStore {
    private val file = AtomicFile(file)
    override fun read(): List<MailMessage> {
        val stream = try { file.openRead() } catch (_: IOException) { return emptyList() }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Invalid mail file")
            val version = input.readInt()
            if (version !in 1..VERSION) throw IOException("Unknown mail version")
            List(input.readInt()) {
                MailMessage(
                    sender = input.readUTF(), subject = input.readUTF(), body = input.readUTF(), to = input.readUTF(),
                    id = input.readLong(), folder = MailFolder.valueOf(input.readUTF()), updatedAt = input.readLong(), remoteKey = if (version >= 2) input.readUTF() else "",
                )
            }
        }
    }

    override fun write(messages: List<MailMessage>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC); data.writeInt(VERSION); data.writeInt(messages.size)
            messages.forEach {
                data.writeUTF(it.sender.take(300)); data.writeUTF(it.subject.take(500)); data.writeUTF(it.body.take(50_000))
                data.writeUTF(it.to.take(300)); data.writeLong(it.id); data.writeUTF(it.folder.name); data.writeLong(it.updatedAt); data.writeUTF(it.remoteKey)
            }
            data.flush(); file.finishWrite(out)
        } catch (e: IOException) { file.failWrite(out); throw e }
    }

    private companion object { const val MAGIC = 0x534B4D4C; const val VERSION = 2 }
}

class MailRepository(
    private val store: MailStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _messages = MutableStateFlow<List<MailMessage>?>(null)
    val messages: StateFlow<List<MailMessage>?> = _messages.asStateFlow()
    private var lastId = 0L
    val error = MutableStateFlow<String?>(null)

    init {
        scope.launch(io) {
            val loaded = try { store.read() } catch (_: Exception) { error.value = "Mail storage cannot be read; existing data has been preserved"; return@launch }
            lastId = loaded.maxOfOrNull { it.id } ?: 0
            _messages.value = loaded
            var written = loaded
            _messages.filterNotNull().conflate().collect {
                if (it !== written) {
                    try { store.write(it); written = it; error.value = null } catch (_: Exception) { error.value = "Mail could not be saved on this phone" }
                }
            }
        }
    }

    @Synchronized
    fun save(id: Long?, to: String, subject: String, body: String, folder: MailFolder): MailMessage {
        check(_messages.value != null) { "Mail has not loaded" }
        val cleanTo = to.trim()
        val old = _messages.value.orEmpty().firstOrNull { it.id == id }
        val message = MailMessage(
            sender = cleanTo.ifEmpty { "No recipient" }, subject = subject.trim(), body = body.trim(), to = cleanTo,
            id = old?.id ?: ++lastId, folder = folder, updatedAt = clock(),
        )
        _messages.value = listOf(message) + _messages.value.orEmpty().filterNot { it.id == message.id }
        return message
    }

    @Synchronized
    fun replaceInbox(messages: List<MailMessage>) {
        val existing = _messages.value ?: throw IllegalStateException("Mail is loading")
        val ids = existing.filter { it.remoteKey.isNotEmpty() }.associate { it.remoteKey to it.id }
        val incoming = messages.map { it.copy(id = ids[it.remoteKey] ?: ++lastId) }
        _messages.value = incoming + existing.filter { it.folder != MailFolder.Inbox }
    }

    fun folder(id: Long, folder: MailFolder) {
        _messages.value = _messages.value.orEmpty().map { if (it.id == id) it.copy(folder = folder, updatedAt = clock()) else it }
    }

    fun delete(id: Long): Pair<MailMessage, Int>? {
        val current = _messages.value ?: return null
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        _messages.value = current.filterNot { it.id == id }
        return current[index] to index
    }

    fun restore(message: MailMessage, index: Int) {
        val list = _messages.value.orEmpty().filterNot { it.id == message.id }.toMutableList()
        list.add(index.coerceIn(0, list.size), message)
        _messages.value = list
    }
}

object Mail {
    @Volatile private var repository: MailRepository? = null
    fun repository(context: Context): MailRepository = repository ?: synchronized(this) {
        repository ?: MailRepository(EncryptedMailStore(context.applicationContext), CoroutineScope(SupervisorJob())).also { repository = it }
    }
}
