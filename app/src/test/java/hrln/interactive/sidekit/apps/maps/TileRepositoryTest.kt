package hrln.interactive.sidekit.apps.maps

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileRepositoryTest {
    private fun png() = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
    }.toByteArray()
    private class Reply(private val code: Int, private val bytes: ByteArray = byteArrayOf()) : HttpURLConnection(URL("https://tile.openstreetmap.org/2/1/1.png")) {
        override fun getResponseCode() = code
        override fun getInputStream() = bytes.inputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
    @Test fun `fresh disk tile loads without network and repeat view uses memory`() = runBlocking {
        val directory = Files.createTempDirectory("tiles").toFile()
        directory.resolve("2-1-1.png").writeBytes(png())
        val repo = TileRepository(directory) { error("fresh disk cache must not fetch") }
        val first = repo.tile(TileKey(2, 1, 1))!!
        assertEquals(256, first.width); assertEquals(256, first.height)
        assertSame(first, repo.tile(TileKey(2, 1, 1)))
    }
    @Test fun `expired tile is revalidated with app identity and survives offline failure`() = runBlocking {
        val directory = Files.createTempDirectory("tiles").toFile()
        val file = directory.resolve("2-1-1.png").apply { writeBytes(png()); setLastModified(System.currentTimeMillis() - 8 * 24 * 60 * 60_000L) }
        val oldTime = file.lastModified()
        val reply = Reply(304)
        val repo = TileRepository(directory) { reply }
        assertNotNull(repo.tile(TileKey(2, 1, 1)))
        assertEquals(OUTDOOR_AGENT, reply.getRequestProperty("User-Agent"))
        assertEquals(oldTime, reply.ifModifiedSince)
        assertTrue(file.lastModified() > oldTime)
        file.setLastModified(oldTime)
        val offline = TileRepository(directory) { Reply(503) }
        assertNotNull(offline.tile(TileKey(2, 1, 1)))
    }
    @Test fun `wrong size server image cannot replace cache`() = runBlocking {
        val directory = Files.createTempDirectory("tiles").toFile()
        val repo = TileRepository(directory) { Reply(200, byteArrayOf(1, 2, 3)) }
        assertNull(repo.tile(TileKey(2, 1, 1)))
        assertFalse(directory.resolve("2-1-1.png").exists())
    }
}
