package hrln.interactive.sidekit.apps.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun playlistsRoundTripThroughAtomicFile() {
        val file = File(folder.root, "playlists.bin")
        val store = FilePlaylistStore(file)
        val playlists = listOf(Playlist(4, "Road trip", listOf(9, 2, 9)))
        store.write(playlists)
        assertEquals(playlists, FilePlaylistStore(file).read())
    }

    @Test fun missingFileIsEmpty() {
        assertEquals(emptyList<Playlist>(), FilePlaylistStore(File(folder.root, "missing")).read())
    }
}
