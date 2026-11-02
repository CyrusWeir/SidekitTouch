package hrln.interactive.sidekit.apps.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** No inbox is invented: these are only messages composed on this phone. */
enum class MailFolder(val label: String) { Draft("Drafts"), Outbox("Outbox"), HandedOff("Handed off"), Inbox("Inbox") }

enum class MailSendResult { HandedOff, Unavailable }

/** Seam for a real IMAP/SMTP account later. The platform implementation only opens a mail app. */
interface MailAccount {
    val configured: Boolean
    suspend fun send(message: MailMessage): MailSendResult
}

class IntentMailAccount(private val context: Context) : MailAccount {
    override val configured = false
    override suspend fun send(message: MailMessage): MailSendResult = withContext(Dispatchers.Main) {
        val uri = Uri.Builder().scheme("mailto").path(message.to)
            .appendQueryParameter("subject", message.subject)
            .appendQueryParameter("body", message.body).build()
        val intent = Intent(Intent.ACTION_SENDTO, uri)
        try {
            context.startActivity(intent)
            MailSendResult.HandedOff
        } catch (_: ActivityNotFoundException) {
            MailSendResult.Unavailable
        } catch (_: SecurityException) {
            MailSendResult.Unavailable
        }
    }
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
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
            List(input.readInt()) {
                MailMessage(
                    sender = input.readUTF(), subject = input.readUTF(), body = input.readUTF(), to = input.readUTF(),
                    id = input.readLong(), folder = MailFolder.valueOf(input.readUTF()), updatedAt = input.readLong(),
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
                data.writeUTF(it.to.take(300)); data.writeLong(it.id); data.writeUTF(it.folder.name); data.writeLong(it.updatedAt)
            }
            data.flush(); file.finishWrite(out)
        } catch (e: IOException) { file.failWrite(out); throw e }
    }

    private companion object { const val MAGIC = 0x534B4D4C; const val VERSION = 1 }
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

    init {
        scope.launch(io) {
            val loaded = try { store.read() } catch (_: Exception) { emptyList() }
            lastId = loaded.maxOfOrNull { it.id } ?: 0
            _messages.value = loaded
            var written = loaded
            _messages.filterNotNull().conflate().collect {
                if (it !== written) {
                    try { store.write(it); written = it } catch (_: IOException) {}
                }
            }
        }
    }

    @Synchronized
    fun save(id: Long?, to: String, subject: String, body: String, folder: MailFolder): MailMessage {
        val cleanTo = to.trim()
        val old = _messages.value.orEmpty().firstOrNull { it.id == id }
        val message = MailMessage(
            sender = cleanTo.ifEmpty { "No recipient" }, subject = subject.trim(), body = body.trim(), to = cleanTo,
            id = old?.id ?: ++lastId, folder = folder, updatedAt = clock(),
        )
        _messages.value = listOf(message) + _messages.value.orEmpty().filterNot { it.id == message.id }
        return message
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
        repository ?: MailRepository(FileMailStore(File(context.applicationContext.filesDir, "mail.bin")), CoroutineScope(SupervisorJob())).also { repository = it }
    }
}
