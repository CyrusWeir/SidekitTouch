package hrln.interactive.sidekit.apps.music

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Where playlists live on disk. Called off the main thread only. */
interface PlaylistStore {
    fun read(): List<Playlist>
    fun write(playlists: List<Playlist>)
}

/**
 * Playlists in one small binary file replaced atomically ([AtomicFile]), the Sidenotes pattern.
 * Tracks are stored by MediaStore id; ids of songs since deleted are skipped when shown.
 */
class FilePlaylistStore(file: File) : PlaylistStore {
    private val file = AtomicFile(file)

    override fun read(): List<Playlist> {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return emptyList()
        }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
            List(input.readInt()) {
                val id = input.readLong()
                val name = input.readUTF()
                val tracks = List(input.readInt()) { input.readLong() }
                Playlist(id, name, tracks)
            }
        }
    }

    override fun write(playlists: List<Playlist>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(playlists.size)
            for (p in playlists) {
                data.writeLong(p.id)
                data.writeUTF(p.name)
                data.writeInt(p.trackIds.size)
                p.trackIds.forEach(data::writeLong)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B504C // "SKPL"
        const val VERSION = 1
    }
}

/**
 * The user's playlists, in memory with conflated writes on [io] behind them (as NotesRepository).
 * Order is creation order, newest last.
 */
class PlaylistRepository(
    private val store: PlaylistStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _playlists = MutableStateFlow<List<Playlist>?>(null)

    /** Null until the file has been read. */
    val playlists: StateFlow<List<Playlist>?> = _playlists.asStateFlow()
    private var lastId = 0L

    init {
        scope.launch(io) {
            val loaded = try {
                store.read()
            } catch (_: IOException) {
                emptyList()
            }
            var written: List<Playlist>? = null
            _playlists.update { current ->
                (if (current == null) loaded else loaded.filter { p -> current.none { it.id == p.id } } + current)
                    .also { if (current == null) written = it }
            }
            synchronized(this@PlaylistRepository) { lastId = maxOf(lastId, loaded.maxOfOrNull { it.id } ?: 0L) }
            _playlists.filterNotNull().conflate().collect { list ->
                if (list !== written) {
                    try {
                        store.write(list)
                        written = list
                    } catch (_: IOException) {
                        // Keep the in-memory copy; the next change retries.
                    }
                }
            }
        }
    }

    fun get(id: Long): Playlist? = _playlists.value?.firstOrNull { it.id == id }

    /** Creates a playlist (blank names become "Playlist N") and returns its id. */
    fun create(name: String, trackIds: List<Long> = emptyList()): Long {
        val id = nextId()
        mutate { list ->
            val title = name.trim().ifEmpty { "Playlist ${list.size + 1}" }
            list + Playlist(id, title, trackIds.distinct())
        }
        return id
    }

    /** Appends tracks not already in the playlist. */
    fun add(id: Long, trackIds: List<Long>) = mutate { list ->
        list.map { p -> if (p.id == id) p.copy(trackIds = p.trackIds + trackIds.filter { it !in p.trackIds }.distinct()) else p }
    }

    fun removeTrack(id: Long, trackId: Long) = mutate { list ->
        list.map { p -> if (p.id == id) p.copy(trackIds = p.trackIds - trackId) else p }
    }

    fun rename(id: Long, name: String) {
        val title = name.trim()
        if (title.isEmpty()) return
        mutate { list -> list.map { if (it.id == id) it.copy(name = title) else it } }
    }

    /** Removes a playlist and returns it with its position (for undo). */
    fun delete(id: Long): Pair<Int, Playlist>? {
        val list = _playlists.value ?: return null
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = list[index]
        mutate { it.filter { p -> p.id != id } }
        return index to removed
    }

    fun restore(index: Int, playlist: Playlist) = mutate { list ->
        if (list.any { it.id == playlist.id }) list else list.toMutableList().apply { add(index.coerceIn(0, size), playlist) }
    }

    private inline fun mutate(crossinline change: (List<Playlist>) -> List<Playlist>) = _playlists.update { change(it.orEmpty()) }

    @Synchronized
    private fun nextId(): Long {
        lastId = maxOf(lastId, _playlists.value?.maxOfOrNull { it.id } ?: 0L) + 1
        return lastId
    }
}

/** Process-wide [PlaylistRepository], created on first use. */
object Playlists {
    @Volatile private var repository: PlaylistRepository? = null

    fun repository(context: Context): PlaylistRepository = repository ?: synchronized(this) {
        repository ?: PlaylistRepository(
            FilePlaylistStore(File(context.applicationContext.filesDir, "music_playlists.bin")),
            CoroutineScope(SupervisorJob()),
        ).also { repository = it }
    }
}
