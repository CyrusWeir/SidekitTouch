package hrln.interactive.sidekit.apps.files

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Where a listing comes from. Encoded as a string ([encode]/[decode]) so it can travel in a
 * navigation route.
 */
sealed interface FilePlace {
    val title: String

    /** A MediaStore collection (needs the storage permission on API 31/32). */
    data class Media(val kind: MediaKind) : FilePlace {
        override val title get() = kind.title
    }

    /** A folder inside a tree the user granted through the Storage Access Framework. */
    data class Tree(val tree: Uri, val documentId: String, override val title: String) : FilePlace

    fun encode(): String = when (this) {
        is Media -> "media:${kind.name}"
        is Tree -> "tree:" + listOf(tree.toString(), documentId, title).joinToString("\u001F")
    }

    companion object {
        fun decode(s: String): FilePlace? = when {
            s.startsWith("media:") -> MediaKind.entries.firstOrNull { it.name == s.removePrefix("media:") }?.let(::Media)
            s.startsWith("tree:") -> s.removePrefix("tree:").split('\u001F').takeIf { it.size == 3 }?.let { (t, d, n) ->
                Tree(Uri.parse(t), d, n)
            }
            else -> null
        }
    }
}

enum class MediaKind(val title: String) {
    Recent("Recents"),
    Documents("Documents"),
    Downloads("Downloads"),
    Images("Photos"),
    Video("Videos"),
    Audio("Music"),
}

/** One file or folder in a listing. */
@Immutable
data class FileEntry(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean = false,
    val canDelete: Boolean = true,
    /** For folders inside a SAF tree: where tapping goes. */
    val folder: FilePlace.Tree? = null,
) {
    val key: String get() = uri.toString()
}

enum class FileSort(val label: String) {
    Name("Name"),
    Newest("Newest"),
    Largest("Largest");

    /** Folders first, then by this order. */
    fun apply(entries: List<FileEntry>): List<FileEntry> {
        val order = when (this) {
            Name -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
            Newest -> compareByDescending<FileEntry> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) }
            Largest -> compareByDescending<FileEntry> { it.size }.thenBy { it.name.lowercase(Locale.ROOT) }
        }
        return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(order))
    }
}

/** A folder tree the user picked (its permission is persisted by the system). */
data class SavedFolder(val tree: Uri, val name: String) {
    val location: FilePlace.Tree get() = FilePlace.Tree(tree, DocumentsContract.getTreeDocumentId(tree), name)
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult

    /** Android asks the user itself before an app deletes another app's media. */
    data class NeedsConsent(val request: IntentSender) : DeleteResult
    data object Failed : DeleteResult
}

/** Everything Files reads from the phone. Swappable for tests. */
interface FilesSource {
    suspend fun list(location: FilePlace): List<FileEntry>
    suspend fun count(kind: MediaKind): Int
    suspend fun search(query: String, folders: List<SavedFolder>): List<FileEntry>
    suspend fun delete(entry: FileEntry): DeleteResult
    fun savedFolders(): List<SavedFolder>
    fun addFolder(tree: Uri): SavedFolder?
    fun removeFolder(folder: SavedFolder)

    /** Free / total bytes of shared storage. */
    fun space(): Pair<Long, Long>
}

/**
 * [FilesSource] over MediaStore (media collections) and the Storage Access Framework (folders the
 * user picks with ACTION_OPEN_DOCUMENT_TREE). No MANAGE_EXTERNAL_STORAGE: arbitrary folders come
 * through SAF grants the user makes one at a time, which Android 11+ expects of a file browser
 * that isn't the system's.
 */
class AndroidFilesSource(context: Context) : FilesSource {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    override suspend fun list(location: FilePlace): List<FileEntry> = withContext(Dispatchers.IO) {
        when (location) {
            is FilePlace.Media -> queryMedia(location.kind, null, LIST_LIMIT)
            is FilePlace.Tree -> listTree(location)
        }
    }

    override suspend fun count(kind: MediaKind): Int = withContext(Dispatchers.IO) {
        val (uri, selection, args) = mediaQuery(kind, null)
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), bundle(selection, args, null, 0), null)?.use { it.count } ?: 0
        } catch (_: SecurityException) {
            0
        }
    }

    override suspend fun search(query: String, folders: List<SavedFolder>): List<FileEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val media = queryMedia(MediaKind.Recent, q, SEARCH_LIMIT)
        val found = ArrayList<FileEntry>(media)
        // Walk picked folders breadth-first, bounded so a huge tree can't stall the phone.
        var visited = 0
        val needle = q.lowercase(Locale.ROOT)
        for (folder in folders) {
            val queue = ArrayDeque(listOf(folder.location))
            while (queue.isNotEmpty() && visited < SEARCH_WALK_LIMIT && found.size < SEARCH_LIMIT) {
                coroutineContext.ensureActive()
                val dir = queue.removeFirst()
                for (e in listTree(dir)) {
                    visited++
                    if (e.name.lowercase(Locale.ROOT).contains(needle)) found += e
                    e.folder?.let(queue::add)
                }
            }
        }
        found.distinctBy { it.key }
    }

    override suspend fun delete(entry: FileEntry): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (DocumentsContract.isDocumentUri(app, entry.uri)) {
                if (DocumentsContract.deleteDocument(resolver, entry.uri)) DeleteResult.Deleted else DeleteResult.Failed
            } else {
                // Another app's media: Android shows its own confirmation (API 30+).
                try {
                    if (resolver.delete(entry.uri, null, null) > 0) DeleteResult.Deleted else DeleteResult.Failed
                } catch (e: RecoverableSecurityException) {
                    DeleteResult.NeedsConsent(MediaStore.createDeleteRequest(resolver, listOf(entry.uri)).intentSender)
                } catch (_: SecurityException) {
                    DeleteResult.NeedsConsent(MediaStore.createDeleteRequest(resolver, listOf(entry.uri)).intentSender)
                }
            }
        } catch (_: Exception) {
            DeleteResult.Failed
        }
    }

    override fun savedFolders(): List<SavedFolder> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { SavedFolder(it.uri, folderName(it.uri)) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    override fun addFolder(tree: Uri): SavedFolder? = try {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(tree, flags)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        SavedFolder(tree, folderName(tree))
    } catch (_: SecurityException) {
        null
    }

    override fun removeFolder(folder: SavedFolder) {
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == folder.tree } ?: return
        var flags = 0
        if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.releasePersistableUriPermission(folder.tree, flags)
        } catch (_: SecurityException) {
        }
    }

    override fun space(): Pair<Long, Long> = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        stat.availableBytes to stat.totalBytes
    } catch (_: IllegalArgumentException) {
        0L to 0L
    }

    private fun folderName(tree: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Folder" }
    }

    private fun listTree(location: FilePlace.Tree): List<FileEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(location.tree, location.documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return try {
            resolver.query(children, projection, null, null, null)?.use { c ->
                val out = ArrayList<FileEntry>(c.count)
                while (c.moveToNext() && out.size < LIST_LIMIT) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    val dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val flags = c.getIntOr(5)
                    out += FileEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(location.tree, id),
                        name = name,
                        mime = mime,
                        size = c.getLongOr(3),
                        modified = c.getLongOr(4),
                        isDirectory = dir,
                        canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
                        folder = if (dir) FilePlace.Tree(location.tree, id, name) else null,
                    )
                }
                out
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryMedia(kind: MediaKind, nameLike: String?, limit: Int): List<FileEntry> {
        val (collection, selection, args) = mediaQuery(kind, nameLike)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        return try {
            resolver.query(collection, projection, bundle(selection, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC", limit), null)?.use { c ->
                val out = ArrayList<FileEntry>(c.count)
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out += FileEntry(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = name,
                        mime = c.getString(2) ?: "application/octet-stream",
                        size = c.getLongOr(3),
                        modified = c.getLongOr(4) * 1000,
                    )
                }
                out
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun mediaQuery(kind: MediaKind, nameLike: String?): Triple<Uri, String?, Array<String>?> {
        val volume = MediaStore.VOLUME_EXTERNAL
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        val uri = when (kind) {
            MediaKind.Images -> MediaStore.Images.Media.getContentUri(volume)
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(volume)
            MediaKind.Audio -> MediaStore.Audio.Media.getContentUri(volume)
            MediaKind.Downloads -> MediaStore.Downloads.getContentUri(volume)
            MediaKind.Recent -> MediaStore.Files.getContentUri(volume)
            MediaKind.Documents -> MediaStore.Files.getContentUri(volume).also {
                clauses += MediaStore.Files.FileColumns.MEDIA_TYPE + "=?"
                args += MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT.toString()
            }
        }
        if (kind == MediaKind.Recent) {
            // Files, not folders (folders have no MIME type in MediaStore).
            clauses += MediaStore.MediaColumns.MIME_TYPE + " IS NOT NULL"
        }
        if (nameLike != null) {
            clauses += MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\'"
            args += "%" + nameLike.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        return Triple(uri, clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun bundle(selection: String?, args: Array<String>?, sort: String?, limit: Int): Bundle = Bundle().apply {
        if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
        if (limit > 0) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    private fun Cursor.getLongOr(i: Int) = if (isNull(i)) 0L else getLong(i)
    private fun Cursor.getIntOr(i: Int) = if (isNull(i)) 0 else getInt(i)

    private companion object {
        const val LIST_LIMIT = 500
        const val SEARCH_LIMIT = 100
        const val SEARCH_WALK_LIMIT = 3_000
    }
}

/** Labels for sizes and dates in file rows. */
object FileFormat {
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1e3)
        bytes < 1_000_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
    }

    fun detail(entry: FileEntry): String = when {
        entry.isDirectory -> "Folder"
        else -> listOf(size(entry.size), date(entry.modified)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun date(millis: Long): String = if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))

    fun count(n: Int) = if (n == 1) "1 file" else "$n files"
}
