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

/** Renders the Music screens at 480 x 640 to app/screenshots/ for comparison with the Figma frames. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val state = MusicUiState()
    private val library = state.library

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun home() = shoot("music_home_71-1618") { MusicHomeScreen(state.home, MusicActions(), {}, {}, {}, {}, {}) }

    @Test fun albums() = shoot("music_albums_71-1483") {
        MusicLibraryListScreen("Albums", library.albums.map(LibraryItem::AlbumItem), MusicActions(), {})
    }

    @Test fun artists() = shoot("music_artists_71-1729") {
        MusicLibraryListScreen("Artists", library.artists.map(LibraryItem::ArtistItem), MusicActions(), {})
    }

    @Test fun album() = shoot("music_album_71-1051") {
        val album = library.album(1)!!
        AlbumScreen(album, library.tracksOf(album), {}, {}, {}, {})
    }

    @Test fun artist() = shoot("music_artist_71-1426") {
        val artist = library.artist(3)!!
        ArtistScreen(artist, library.albumsBy(artist), {}, {}, {})
    }

    @Test fun nowPlaying() = shoot("music_now_playing_71-759") {
        NowPlayingScreen(state.nowPlaying, isPlaying = true, {}, {}, {}, {}, {}, {})
    }

    @Test fun searchBlank() = shoot("music_search_blank_71-1929") { MusicSearchScreen("", emptyList(), {}, MusicActions(), {}) }

    @Test fun search() = shoot("music_search_71-1890") {
        MusicSearchScreen("Steve La", library.search("Steve La"), {}, MusicActions(), {})
    }
}
