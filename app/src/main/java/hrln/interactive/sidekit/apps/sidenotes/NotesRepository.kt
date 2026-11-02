package hrln.interactive.sidekit.apps.sidenotes

import android.content.Context
import androidx.core.util.AtomicFile
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * A note. [title] and [preview] are derived once here, when the note is loaded or saved, rather
 * than on every recomposition of its list row.
 */
@Immutable
data class Note(val id: Long, val body: String, val updatedAt: Long) {
    val title: String
    val preview: String

    init {
        var first: String? = null
        var second: String? = null
        for (line in body.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (first == null) first = t else { second = t; break }
        }
        title = first?.take(TITLE_MAX) ?: "Untitled"
        preview = second?.take(PREVIEW_MAX).orEmpty()
    }

    private companion object {
        const val TITLE_MAX = 80
        const val PREVIEW_MAX = 120
    }
}

/** Where notes live on disk. Called off the main thread only. */
interface NoteStore {
    fun read(): List<Note>
    fun write(notes: List<Note>)
}

/**
 * Notes in one small binary file, replaced atomically ([AtomicFile]: write to a temp file,
 * fsync, rename) so a crash or power cut mid-write never loses the previous copy.
 *
 * Deliberately not Room/SQLite: a few hundred notes are tens of KB, a database would cost a
 * process-wide connection pool, its own threads and ~1 MB of code for no gain at this size.
 */
class FileNoteStore(file: File) : NoteStore {
    private val file = AtomicFile(file)

    override fun read(): List<Note> {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return emptyList()
        }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            if (input.readInt() != MAGIC) return emptyList()
            val version = input.readInt()
            if (version != VERSION) return emptyList()
            val count = input.readInt()
            List(count) {
                val id = input.readLong()
                val updated = input.readLong()
                val bytes = ByteArray(input.readInt())
                input.readFully(bytes)
                Note(id, String(bytes, Charsets.UTF_8), updated)
            }
        }
    }

    override fun write(notes: List<Note>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(notes.size)
            for (note in notes) {
                data.writeLong(note.id)
                data.writeLong(note.updatedAt)
                val bytes = note.body.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B4E54 // "SKNT"
        const val VERSION = 1
    }
}

/**
 * The one copy of the user's notes in memory, shared by every visit to Sidenotes.
 *
 * Reads and edits are synchronous and in-memory; persistence happens behind them on [io]. Writes
 * are conflated, so a burst of edits (or deletes while swiping through a list) becomes a single
 * file write of the latest state, never a queue of stale ones.
 */
class NotesRepository(
    private val store: NoteStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _notes = MutableStateFlow<List<Note>?>(null)

    /** Newest first. Null until the file has been read. */
    val notes: StateFlow<List<Note>?> = _notes.asStateFlow()

    private var lastId = 0L

    init {
        scope.launch(io) {
            val loaded = try {
                store.read()
            } catch (_: IOException) {
                emptyList()
            }
            var written: List<Note>? = null
            _notes.update { current ->
                val merged = if (current == null) loaded else current + loaded.filter { n -> current.none { it.id == n.id } }
                merged.sortedWith(NEWEST_FIRST).also { if (current == null) written = it }
            }
            synchronized(this@NotesRepository) {
                lastId = maxOf(lastId, loaded.maxOfOrNull { it.id } ?: 0L)
            }
            _notes.filterNotNull().conflate().collect { notes ->
                if (notes !== written) {
                    try {
                        store.write(notes)
                        written = notes
                    } catch (_: IOException) {
                        // Keep the in-memory copy; the next edit retries the write.
                    }
                }
            }
        }
    }

    fun get(id: Long): Note? = _notes.value?.firstOrNull { it.id == id }

    /**
     * Creates or updates a note. A blank body is not stored: nothing is created, and an existing
     * note is left for the caller to delete (so it can offer undo). Returns the note's id, or
     * null if nothing was stored.
     */
    fun save(id: Long?, body: String): Long? {
        val text = body.trimEnd()
        if (text.isBlank()) return null
        val existing = id?.let(::get)
        if (existing != null) {
            if (existing.body == text) return existing.id
            val updated = existing.copy(body = text, updatedAt = clock())
            mutate { list -> listOf(updated) + list.filter { it.id != existing.id } }
            return existing.id
        }
        val note = Note(nextId(), text, clock())
        mutate { list -> listOf(note) + list }
        return note.id
    }

    /** Removes a note and returns it (for undo), or null if there was none. */
    fun delete(id: Long): Note? {
        val note = get(id) ?: return null
        mutate { list -> list.filter { it.id != id } }
        return note
    }

    /** Puts a deleted note back where it was (notes are ordered by [Note.updatedAt]). */
    fun restore(note: Note) = mutate { list ->
        if (list.any { it.id == note.id }) list else (list + note).sortedWith(NEWEST_FIRST)
    }

    private inline fun mutate(crossinline change: (List<Note>) -> List<Note>) =
        _notes.update { change(it.orEmpty()) }

    @Synchronized
    private fun nextId(): Long {
        lastId = maxOf(lastId, _notes.value?.maxOfOrNull { it.id } ?: 0L) + 1
        return lastId
    }

    private companion object {
        val NEWEST_FIRST = compareByDescending<Note> { it.updatedAt }.thenByDescending { it.id }
    }
}

/** Process-wide [NotesRepository], created on first use. */
object Sidenotes {
    @Volatile private var repository: NotesRepository? = null

    fun repository(context: Context): NotesRepository = repository ?: synchronized(this) {
        repository ?: NotesRepository(
            FileNoteStore(File(context.applicationContext.filesDir, "sidenotes.bin")),
            CoroutineScope(SupervisorJob()),
        ).also { repository = it }
    }
}
