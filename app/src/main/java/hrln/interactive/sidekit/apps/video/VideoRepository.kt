package hrln.interactive.sidekit.apps.video

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import hrln.interactive.sidekit.apps.media.changes
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Immutable
data class Video(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val dateTakenMs: Long = 0,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

/** Reads the phone's videos. Called off the main thread only. */
interface VideoSource {
    fun videos(): List<Video>
    fun video(id: Long): Video?
    fun changes(): Flow<Unit>
}

class MediaStoreVideoSource(private val resolver: ContentResolver) : VideoSource {
    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.TITLE,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_TAKEN,
    )

    override fun videos(): List<Video> = query(null, null)

    override fun video(id: Long): Video? = query("${MediaStore.Video.Media._ID} = ?", arrayOf(id.toString())).firstOrNull()

    private fun query(selection: String?, args: Array<String>?): List<Video> =
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media._ID} DESC",
        )?.use { c ->
            // One row is ~100 bytes; even a few thousand videos is a small list, so no paging here.
            val out = ArrayList<Video>(c.count)
            while (c.moveToNext()) {
                val taken = c.getLong(4)
                out += Video(c.getLong(0), videoTitle(c.getString(1) ?: c.getString(2).orEmpty(), taken), c.getLong(3), taken)
            }
            out
        }.orEmpty()

    override fun changes(): Flow<Unit> = resolver.changes(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
}

private val CAMERA_NAME = Regex("^(VID|PXL|MOV)_?\\d{8}.*", RegexOption.IGNORE_CASE)

/**
 * A readable title: camera file names ("VID_20260717_203453") become their date
 * ("17 Jul 2026, 20:34"); anything else is shown as named.
 */
fun videoTitle(title: String, dateTakenMs: Long, locale: Locale = Locale.getDefault()): String {
    val t = title.substringBeforeLast('.').ifBlank { title }
    if (dateTakenMs <= 0 || !CAMERA_NAME.matches(t)) return t.ifBlank { "Video" }
    return SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(dateTakenMs))
}
