package hrln.interactive.sidekit.apps.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = listOf(Track(1, "Our Mission", 1, 3, "Yoshiruma Terui", "Hidden Inventory", 180_000))
    private val library = MusicLibrary.from(tracks, listOf(Playlist(1, "Playlist 1", listOf(1))))
    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
    @Test fun home() = shoot("music_home_71-1618") { MusicHomeScreen(library.home(), MusicActions(), {}, {}, {}, {}, {}) }
    @Test fun albums() = shoot("music_albums_71-1483") { MusicLibraryListScreen("Albums", library.albums.map(LibraryItem::AlbumItem), MusicActions(), {}) }
    @Test fun album() = shoot("music_album_71-1051") { AlbumScreen(library.albums.first(), tracks, {}, {}, {}, {}, {}) }
    @Test fun nowPlaying() = shoot("music_now_playing_71-759") { NowPlayingScreen(PlaybackState(PlayQueue.of(tracks, 0, false, RepeatMode.Off), true, durationMs = 180_000), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun search() = shoot("music_search_blank_71-1929") { MusicSearchScreen("", emptyList(), {}, MusicActions(), {}) }
}
