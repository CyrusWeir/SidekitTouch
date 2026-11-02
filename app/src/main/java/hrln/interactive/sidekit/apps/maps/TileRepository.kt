package hrln.interactive.sidekit.apps.maps

import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

data class TileKey(val zoom: Int, val x: Int, val y: Int)
data class MapPoint(val x: Double, val y: Double)
fun project(place: Place, zoom: Int): MapPoint {
    val n = (1 shl zoom).toDouble()
    val lat = Math.toRadians(place.latitude.coerceIn(-85.05112878, 85.05112878))
    return MapPoint((place.longitude + 180) / 360 * n, (1 - ln(tan(lat) + 1 / cos(lat)) / Math.PI) / 2 * n)
}

/** The LRU holds 12 decoded 256px ARGB tiles (3 MiB); the visible frame also retains its tiles.
 * Compressed disk cache is capped at 16 MiB. Only viewport tiles are requested, never prefetched. */
class TileRepository(
    private val directory: File,
    private val connect: (TileKey) -> HttpURLConnection = { key ->
        URL("https://tile.openstreetmap.org/${key.zoom}/${key.x}/${key.y}.png").openConnection() as HttpURLConnection
    },
) {
    private val memory = object : LruCache<TileKey, ImageBitmap>(12) {}
    suspend fun tile(key: TileKey): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(key)?.let { return@withContext it }
        directory.mkdirs()
        val file = File(directory, "${key.zoom}-${key.x}-${key.y}.png")
        val disk = AtomicFile(file)
        val expired = System.currentTimeMillis() - file.lastModified() > 7 * 24 * 60 * 60_000L
        if (!file.exists() || expired) {
            val connection = connect(key)
            val disconnect = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { connection.disconnect() } }
            try {
                connection.connectTimeout = 8_000; connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", OUTDOOR_AGENT)
                if (file.exists()) connection.ifModifiedSince = file.lastModified()
                when (connection.responseCode) {
                    304 -> file.setLastModified(System.currentTimeMillis())
                    200 -> {
                        val bytes = connection.inputStream.use { it.readBytes() }
                        ensureActive()
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        check(options.outWidth == 256 && options.outHeight == 256)
                        val out = disk.startWrite()
                        try { out.write(bytes); disk.finishWrite(out) } catch (e: Exception) { disk.failWrite(out); throw e }
                    }
                    else -> if (!file.exists()) return@withContext null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (!file.exists()) return@withContext null }
            finally { disconnect.cancel(); connection.disconnect() }
        }
        val bitmap = try { disk.openRead().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() } catch (_: Exception) { null }
        bitmap?.let { memory.put(key, it) }
        trimDisk()
        bitmap
    }
    private fun trimDisk() {
        val files = directory.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var bytes = 0L
        for (file in files) { bytes += file.length(); if (bytes > 16 * 1024 * 1024) file.delete() }
    }
    fun clearMemory() = memory.evictAll()
}
