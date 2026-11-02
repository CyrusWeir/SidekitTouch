package hrln.interactive.sidekit.apps.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MusicUiState(
    val library: MusicLibrary = MusicLibrary.Empty,
    val playback: PlaybackState = PlaybackState(),
    val loaded: Boolean = false,
    val query: String = "",
) {
    val home: List<LibraryItem> get() = library.home()
    val results: List<LibraryItem> get() = library.search(query)
    val nowPlaying: Track? get() = playback.track
}

/** Real MediaStore library and the process-wide player, shared by every Music screen. */
class MusicViewModel(
    private val source: MusicSource,
    private val playlists: PlaylistRepository,
    private val player: MusicPlayer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)

    val state: StateFlow<MusicUiState> = combine(
        sourceLibrary(),
        player.state,
        loaded,
        query,
        ::MusicUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState(playback = player.state.value))

    private fun sourceLibrary() = combine(source.changes(), playlists.playlists) { _, saved ->
        val tracks = withContext(io) { runCatching { source.tracks() }.getOrDefault(emptyList()) }
        loaded.value = true
        MusicLibrary.from(tracks, saved.orEmpty())
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun skipNext() = player.next()
    fun skipPrevious() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setShuffle(on: Boolean) = player.setShuffle(on)
    fun cycleRepeat() = player.cycleRepeat()

    fun playAlbum(albumId: Long, trackId: Long? = null) {
        val album = state.value.library.album(albumId) ?: return
        play(state.value.library.tracksOf(album), trackId)
    }

    fun playArtist(artistId: Long) {
        val artist = state.value.library.artist(artistId) ?: return
        play(state.value.library.tracksBy(artist))
    }

    fun playPlaylist(playlistId: Long) {
        val playlist = state.value.library.playlist(playlistId) ?: return
        play(state.value.library.tracksOf(playlist))
    }

    fun playTrack(trackId: Long) {
        val track = state.value.library.track(trackId) ?: return
        playAlbum(track.albumId, trackId)
    }

    private fun play(tracks: List<Track>, startId: Long? = null) {
        if (tracks.isEmpty()) return
        player.playQueue(tracks, startId?.let { id -> tracks.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0)
    }

    private fun List<Track>.indexOfFirstId(id: Long) = indexOfFirst { it.id == id }

    /** The bookmark tiles make a persistent playlist from the current album or track. */
    fun saveTracks(name: String, tracks: List<Track>) {
        if (tracks.isNotEmpty()) playlists.create(name, tracks.map { it.id })
    }

    fun createPlaylist() = playlists.create("")

    fun updateQuery(value: String) { query.value = value }
    fun clearSearch() { query.value = "" }
}
