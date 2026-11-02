package hrln.interactive.sidekit.apps.media

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small bitmaps for grids, rows and the action bar (photo tiles, video rows, album art), shared
 * by all the media apps so one budget bounds them all.
 *
 * - Loaded with [ContentResolver.loadThumbnail], which reads MediaProvider's cached thumbnail
 *   rather than decoding the original, then cropped and scaled to exactly the size drawn, in
 *   RGB_565 (no alpha in photos): a 113 x 104 tile costs 23 KB.
 * - The cache is an LRU sized from [ActivityManager.getMemoryClass]: 1/32 of the heap, 8 MB on
 *   the Sidephone's 256 MB class, roughly 350 tiles. Bitmaps are never recycled by hand; an entry
 *   that falls out is freed by the GC once no tile draws it.
 * - At most two decodes run at once, so a fling queues work instead of flooding the IO pool, and
 *   a tile that scrolls away cancels its load.
 */
object Thumbnails {
    @Volatile private var cache: LruCache<String, Bitmap>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decode = Dispatchers.IO.limitedParallelism(2)

    private fun cache(context: Context): LruCache<String, Bitmap> = cache ?: synchronized(this) {
        cache ?: run {
            val memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 128
            object : LruCache<String, Bitmap>(memoryClassMb * 1024 * 1024 / 32) {
                override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
            }.also { cache = it }
        }
    }

    private fun key(uri: Uri, w: Int, h: Int) = "$uri@${w}x$h"

    fun cached(context: Context, uri: Uri, w: Int, h: Int): Bitmap? = cache(context)[key(uri, w, h)]

    /** Loads [uri]'s thumbnail cropped to [w] x [h] px. Null if it has none or can't be read. */
    suspend fun load(context: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val cache = cache(context)
        val key = key(uri, w, h)
        cache[key]?.let { return it }
        return withContext(decode) {
            cache[key]?.let { return@withContext it }
            val signal = CancellationSignal()
            // A tile that leaves the screen cancels its decode inside MediaProvider too.
            val cancel = coroutineContext[Job]?.invokeOnCompletion { if (it != null) signal.cancel() }
            try {
                val raw = context.contentResolver.loadThumbnail(uri, Size(w, h), signal)
                cropTo(raw, w, h).also { cache.put(key, it) }
            } catch (_: Exception) {
                null
            } finally {
                cancel?.dispose()
            }
        }
    }

    fun clear() {
        cache?.evictAll()
    }

    /** Centre-crops [src] to fill [w] x [h], in RGB_565. */
    internal fun cropTo(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val cropW = (w / scale).roundToInt().coerceIn(1, src.width)
        val cropH = (h / scale).roundToInt().coerceIn(1, src.height)
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        if (src !== out) src.recycle()
        return out
    }
}

/**
 * Draws [uri]'s thumbnail filling this box ([width] x [height]), or nothing while it loads (the
 * box's own background shows through). Drawn in the draw phase: a loaded thumbnail invalidates
 * one draw, not a recomposition of the row.
 */
@Composable
fun Thumbnail(uri: Uri?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val w = with(density) { width.roundToPx() }
    val h = with(density) { height.roundToPx() }
    val inspection = LocalInspectionMode.current
    var image by remember(uri, w, h) {
        mutableStateOf(uri?.let { Thumbnails.cached(context, it, w, h)?.asImageBitmap() })
    }
    if (uri != null && image == null && !inspection) {
        LaunchedEffect(uri, w, h) {
            image = Thumbnails.load(context, uri, w, h)?.asImageBitmap()
        }
    }
    Box(modifier.drawBehind { image?.let { drawThumbnail(it) } })
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumbnail(image: ImageBitmap) {
    val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height), dstSize = dst)
}
