package hrln.interactive.sidekit.apps.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Music.route
private val HOME = "$ROOT/home"
private val PLAYLISTS = "$ROOT/playlists"
private val ALBUMS = "$ROOT/albums"
private val ARTISTS = "$ROOT/artists"
private val ALBUM = "$ROOT/album/{id}"
private val ARTIST = "$ROOT/artist/{id}"
private val NOW_PLAYING = "$ROOT/now-playing"
private val SEARCH = "$ROOT/search"

private fun albumRoute(id: Int) = "$ROOT/album/$id"
private fun artistRoute(id: Int) = "$ROOT/artist/$id"
private val idArg = listOf(navArgument("id") { type = NavType.IntType })

fun NavGraphBuilder.musicGraph(nav: NavHostController) {
    // "← Music" returns to the Music home.
    val backToMusic: () -> Unit = { nav.popBackStack(HOME, inclusive = false) }

    navigation(startDestination = HOME, route = ROOT) {
        composable(HOME) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            MusicHomeScreen(
                items = state.home,
                actions = vm.actions(nav),
                onPlaylists = { nav.navigate(PLAYLISTS) },
                onAlbums = { nav.navigate(ALBUMS) },
                onArtists = { nav.navigate(ARTISTS) },
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
                onSearch = { vm.clearSearch(); nav.navigate(SEARCH) },
            )
        }
        composable(PLAYLISTS) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            MusicLibraryListScreen("Playlists", state.library.playlists.map(LibraryItem::PlaylistItem), vm.actions(nav), backToMusic)
        }
        composable(ALBUMS) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            MusicLibraryListScreen("Albums", state.library.albums.map(LibraryItem::AlbumItem), vm.actions(nav), backToMusic)
        }
        composable(ARTISTS) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            MusicLibraryListScreen("Artists", state.library.artists.map(LibraryItem::ArtistItem), vm.actions(nav), backToMusic)
        }
        composable(ALBUM, arguments = idArg) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val album = state.library.album(entry.arguments?.getInt("id") ?: -1) ?: return@composable
            AlbumScreen(
                album = album,
                tracks = state.library.tracksOf(album),
                onArtist = { nav.navigate(artistRoute(album.artistId)) },
                onPlay = { vm.playAlbum(album.id); nav.navigate(NOW_PLAYING) },
                onTrack = { vm.playTrack(it.id); nav.navigate(NOW_PLAYING) },
                onBack = backToMusic,
            )
        }
        composable(ARTIST, arguments = idArg) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val artist = state.library.artist(entry.arguments?.getInt("id") ?: -1) ?: return@composable
            ArtistScreen(
                artist = artist,
                albums = state.library.albumsBy(artist),
                onPlay = { vm.playArtist(artist.id); nav.navigate(NOW_PLAYING) },
                onAlbum = { nav.navigate(albumRoute(it.id)) },
                onBack = backToMusic,
            )
        }
        composable(NOW_PLAYING) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val track = state.nowPlaying
            val album = track?.let { state.library.album(it.albumId) }
            NowPlayingScreen(
                track = track,
                isPlaying = state.isPlaying,
                onAlbum = { album?.let { nav.navigate(albumRoute(it.id)) } },
                onArtist = { album?.let { nav.navigate(artistRoute(it.artistId)) } },
                onPlayPause = vm::togglePlayPause,
                onPrevious = vm::skipPrevious,
                onNext = vm::skipNext,
                onBack = backToMusic,
            )
        }
        composable(SEARCH) { entry ->
            val vm = entry.musicViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            MusicSearchScreen(state.query, state.results, vm::updateQuery, vm.actions(nav), backToMusic)
        }
    }
}

/** Row taps: playlists and tracks start playing and open Now Playing; albums/artists open their page. */
private fun MusicViewModel.actions(nav: NavHostController) = MusicActions(
    openPlaylist = { playPlaylist(it.id); nav.navigate(NOW_PLAYING) },
    openAlbum = { nav.navigate(albumRoute(it.id)) },
    openArtist = { nav.navigate(artistRoute(it.id)) },
    openTrack = { playTrack(it.id); nav.navigate(NOW_PLAYING) },
)

/** One ViewModel per visit to the app, shared by its screens. */
@Composable
private fun NavBackStackEntry.musicViewModel(nav: NavHostController): MusicViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    return viewModel(parent)
}
