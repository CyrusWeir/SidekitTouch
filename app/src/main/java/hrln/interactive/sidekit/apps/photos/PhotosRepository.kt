package hrln.interactive.sidekit.apps.photos

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.core.util.AtomicFile
import hrln.interactive.sidekit.apps.media.changes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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

/** A photo or video in MediaStore. Only what a tile and the viewer need; pixels load lazily. */
@Immutable
data class MediaItem(
    val id: Long,
    val isVideo: Boolean = false,
    val durationMs: Long = 0,
    val mimeType: String = if (isVideo) "video/*" else "image/*",
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
}

/**
 * Which slice of the library a grid shows. [route] is its form in navigation routes.
 *
 * Smart albums are the ones the Albums mock shows that can be real here: Recent (the last 30
 * days), Favourites (a local list, MediaStore has no portable favourite flag on API 31), Videos
 * and Camera (DCIM/Camera, where SideKit's Camera saves).
 */
sealed class AlbumKey(val route: String) {
    data object All : AlbumKey("all")
    data object Recent : AlbumKey("recent")
    data object Favourites : AlbumKey("favourites")
    data object Videos : AlbumKey("videos")
    data object Camera : AlbumKey("camera")
    data class Bucket(val id: Long) : AlbumKey("b$id")

    companion object {
        val smart = listOf(Recent, Favourites, Videos, Camera)

        fun parse(route: String?): AlbumKey = when (route) {
            Recent.route -> Recent
            Favourites.route -> Favourites
            Videos.route -> Videos
            Camera.route -> Camera
            null, All.route -> All
            else -> route.removePrefix("b").toLongOrNull()?.let(::Bucket) ?: All
        }
    }
}

/** @property itemCount shown on folder rows ("50 items"); null for the smart albums. */
@Immutable
data class PhotoAlbum(val key: AlbumKey, val name: String, val itemCount: Int? = null)

fun AlbumKey.smartName(): String? = when (this) {
    AlbumKey.All -> "Photos"
    AlbumKey.Recent -> "Recent"
    AlbumKey.Favourites -> "Favourites"
    AlbumKey.Videos -> "Videos"
    AlbumKey.Camera -> "Camera"
    is AlbumKey.Bucket -> null
}

/** A MediaStore `WHERE` clause. */
data class Selection(val sql: String, val args: Array<String>) {
    override fun equals(other: Any?) = other is Selection && sql == other.sql && args.contentEquals(other.args)
    override fun hashCode() = sql.hashCode() * 31 + args.contentHashCode()
}

private const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
private const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
private const val RECENT_SECONDS = 30L * 24 * 60 * 60

/**
 * The selection for [album], or null when it can only be empty (no favourites yet). Pure, so the
 * album rules are unit-tested without MediaStore.
 */
fun AlbumKey.selection(favourites: Set<Long>, nowSeconds: Long): Selection? {
    val type = MediaStore.Files.FileColumns.MEDIA_TYPE
    val both = "$type IN ($IMAGE,$VIDEO)"
    return when (this) {
        AlbumKey.All -> Selection(both, emptyArray())
        AlbumKey.Recent -> Selection("$both AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf((nowSeconds - RECENT_SECONDS).toString()))
        AlbumKey.Videos -> Selection("$type = $VIDEO", emptyArray())
        AlbumKey.Camera -> Selection("$both AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/Camera/%"))
        is AlbumKey.Bucket -> Selection("$both AND ${MediaStore.MediaColumns.BUCKET_ID} = ?", arrayOf(id.toString()))
        // Ids are numbers we wrote ourselves, so inlining them is safe; capped to stay well under
        // SQLite's expression limits.
        AlbumKey.Favourites -> if (favourites.isEmpty()) null else Selection("$both AND ${MediaStore.MediaColumns._ID} IN (${favourites.take(MAX_FAVOURITES).joinToString(",")})", emptyArray())
    }
}

const val MAX_FAVOURITES = 900

/** Groups (bucket id, name) rows into folder albums, biggest first. */
fun groupBuckets(rows: Sequence<Pair<Long, String?>>): List<PhotoAlbum> {
    val counts = LinkedHashMap<Long, Int>()
    val names = HashMap<Long, String>()
    for ((id, name) in rows) {
        counts[id] = (counts[id] ?: 0) + 1
        if (name != null) names.putIfAbsent(id, name)
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { names[it.key].orEmpty().lowercase() })
        .map { PhotoAlbum(AlbumKey.Bucket(it.key), names[it.key] ?: "Untitled", it.value) }
}

/** Reads the photo library. Called off the main thread only. */
interface PhotoSource {
    fun page(album: AlbumKey, favourites: Set<Long>, offset: Int, limit: Int): List<MediaItem>
    fun folders(): List<PhotoAlbum>
    fun changes(): Flow<Unit>
}

class MediaStorePhotoSource(private val resolver: ContentResolver, private val clock: () -> Long = System::currentTimeMillis) : PhotoSource {
    private val files: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun page(album: AlbumKey, favourites: Set<Long>, offset: Int, limit: Int): List<MediaItem> {
        val selection = album.selection(favourites, clock() / 1000) ?: return emptyList()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.sql)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selection.args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        return resolver.query(files, projection, args, null)?.use { c ->
            val out = ArrayList<MediaItem>(c.count)
            while (c.moveToNext()) {
                val video = c.getInt(1) == VIDEO
                out += MediaItem(c.getLong(0), video, if (video) c.getLong(2) else 0L, c.getString(3) ?: if (video) "video/*" else "image/*")
            }
            out
        }.orEmpty()
    }

    override fun folders(): List<PhotoAlbum> {
        val selection = AlbumKey.All.selection(emptySet(), 0)!!
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        // Two narrow columns per row; grouping in memory because MediaStore has no GROUP BY for apps.
        return resolver.query(files, projection, selection.sql, selection.args, null)?.use { c ->
            groupBuckets(generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null })
        }.orEmpty()
    }

    override fun changes(): Flow<Unit> = resolver.changes(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
}

// ---- Favourites --------------------------------------------------------------------------

/** Where favourite ids live on disk. Called off the main thread only. */
interface IdSetStore {
    fun read(): Set<Long>
    fun write(ids: Set<Long>)
}

/** A set of ids in one small file replaced atomically, as Sidenotes stores notes. */
class FileIdSetStore(file: File) : IdSetStore {
    private val file = AtomicFile(file)

    override fun read(): Set<Long> {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return emptySet()
        }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptySet()
            val count = input.readInt()
            LinkedHashSet<Long>(count).apply { repeat(count) { add(input.readLong()) } }
        }
    }

    override fun write(ids: Set<Long>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(ids.size)
            ids.forEach(data::writeLong)
            data.flush()
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B4656 // "SKFV"
        const val VERSION = 1
    }
}

/**
 * Favourite photo ids, in memory with conflated writes behind them on [io] (the NotesRepository
 * pattern): toggling a heart is instant and a burst of toggles is one file write.
 */
class FavouritesRepository(
    private val store: IdSetStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _ids = MutableStateFlow<Set<Long>?>(null)

    /** Null until the file has been read. */
    val ids: StateFlow<Set<Long>?> = _ids.asStateFlow()

    init {
        scope.launch(io) {
            val loaded = try {
                store.read()
            } catch (_: IOException) {
                emptySet()
            }
            var written: Set<Long>? = null
            _ids.update { current -> (if (current == null) loaded else loaded + current).also { if (current == null) written = it } }
            _ids.filterNotNull().conflate().collect { ids ->
                if (ids !== written) {
                    try {
                        store.write(ids)
                        written = ids
                    } catch (_: IOException) {
                        // Keep the in-memory copy; the next change retries.
                    }
                }
            }
        }
    }

    fun isFavourite(id: Long): Boolean = _ids.value?.contains(id) == true

    fun toggle(id: Long) = _ids.update { current ->
        val set = current.orEmpty()
        if (id in set) set - id else if (set.size >= MAX_FAVOURITES) set else set + id
    }

    fun remove(id: Long) = _ids.update { current -> current?.let { if (id in it) it - id else it } }
}

/** Process-wide photo services, created on first use. */
object Photos {
    @Volatile private var favourites: FavouritesRepository? = null

    fun favourites(context: Context): FavouritesRepository = favourites ?: synchronized(this) {
        favourites ?: FavouritesRepository(
            FileIdSetStore(File(context.applicationContext.filesDir, "photos_favourites.bin")),
            CoroutineScope(SupervisorJob()),
        ).also { favourites = it }
    }
}
