package hrln.interactive.sidekit.apps.music

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Artist(val id: Int, val name: String)
data class Album(val id: Int, val title: String, val artistId: Int, val trackIds: List<Int>)
data class Track(val id: Int, val title: String, val albumId: Int)
data class Playlist(val id: Int, val name: String, val trackIds: List<Int>)

/** A row in the home list or the search results. */
sealed interface LibraryItem {
    data class PlaylistItem(val playlist: Playlist) : LibraryItem
    data class AlbumItem(val album: Album) : LibraryItem
    data class ArtistItem(val artist: Artist) : LibraryItem
    data class TrackItem(val track: Track) : LibraryItem
}

data class MusicLibrary(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
    val playlists: List<Playlist>,
) {
    fun artist(id: Int): Artist? = artists.firstOrNull { it.id == id }
    fun album(id: Int): Album? = albums.firstOrNull { it.id == id }
    fun track(id: Int): Track? = tracks.firstOrNull { it.id == id }
    fun playlist(id: Int): Playlist? = playlists.firstOrNull { it.id == id }
    fun tracksOf(album: Album): List<Track> = album.trackIds.mapNotNull(::track)
    fun albumsBy(artist: Artist): List<Album> = albums.filter { it.artistId == artist.id }
    fun artistOf(album: Album): Artist? = artist(album.artistId)

    /**
     * Case-insensitive substring search over artists, albums, tracks and playlists (in that
     * order). A blank query returns nothing ("Search blank", 71:1929).
     */
    fun search(query: String): List<LibraryItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        fun String.matches() = contains(q, ignoreCase = true)
        return artists.filter { it.name.matches() }.map(LibraryItem::ArtistItem) +
            albums.filter { it.title.matches() }.map(LibraryItem::AlbumItem) +
            tracks.filter { it.title.matches() }.map(LibraryItem::TrackItem) +
            playlists.filter { it.name.matches() }.map(LibraryItem::PlaylistItem)
    }
}

data class MusicUiState(
    val library: MusicLibrary = MusicSampleData.library,
    val home: List<LibraryItem> = MusicSampleData.home,
    /** Track ids of the album/playlist being played. */
    val queue: List<Int> = MusicSampleData.initialQueue,
    val queueIndex: Int = MusicSampleData.initialQueueIndex,
    val isPlaying: Boolean = true,
    val query: String = "",
    val results: List<LibraryItem> = emptyList(),
) {
    val nowPlaying: Track? get() = queue.getOrNull(queueIndex)?.let(library::track)
}

/**
 * State for the whole Music app, shared by its screens. Playback is state only (no audio).
 *
 * Skipping wraps around the queue: next on the last track goes to the first, previous on the
 * first goes to the last. Skipping keeps the current play/pause state.
 */
class MusicViewModel(initial: MusicUiState = MusicUiState()) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    fun togglePlayPause() = _state.update {
        if (it.nowPlaying == null) it else it.copy(isPlaying = !it.isPlaying)
    }

    fun skipNext() = skip(+1)

    fun skipPrevious() = skip(-1)

    private fun skip(delta: Int) = _state.update {
        if (it.queue.isEmpty()) it else it.copy(queueIndex = Math.floorMod(it.queueIndex + delta, it.queue.size))
    }

    /** Plays [album] from [trackId] (or its first track). No-op for an album without tracks. */
    fun playAlbum(albumId: Int, trackId: Int? = null) = _state.update { state ->
        val album = state.library.album(albumId) ?: return@update state
        state.play(album.trackIds, trackId)
    }

    /** Plays every album by the artist in library order. */
    fun playArtist(artistId: Int) = _state.update { state ->
        val artist = state.library.artist(artistId) ?: return@update state
        state.play(state.library.albumsBy(artist).flatMap { it.trackIds }, null)
    }

    fun playPlaylist(playlistId: Int) = _state.update { state ->
        val playlist = state.library.playlist(playlistId) ?: return@update state
        state.play(playlist.trackIds, null)
    }

    /** Plays a single track within its album. */
    fun playTrack(trackId: Int) {
        val track = _state.value.library.track(trackId) ?: return
        playAlbum(track.albumId, trackId)
    }

    fun updateQuery(query: String) = _state.update {
        it.copy(query = query, results = it.library.search(query))
    }

    fun clearSearch() = updateQuery("")

    private fun MusicUiState.play(trackIds: List<Int>, startId: Int?): MusicUiState {
        if (trackIds.isEmpty()) return this
        val index = startId?.let(trackIds::indexOf)?.takeIf { it >= 0 } ?: 0
        return copy(queue = trackIds, queueIndex = index, isPlaying = true)
    }
}

object MusicSampleData {
    // Names copied from the Music mocks (71:1618, 71:1483, 71:1729, 71:1051, 71:1426, 71:759).
    private const val TERUI = 3
    private const val HIDDEN_INVENTORY = 1
    private const val CULLING_GAMES = 2
    private const val A_LITTLE_LESSON = 3

    val artists = listOf(
        Artist(1, "Steve Lacy"),
        Artist(2, "Kevin Penkin"),
        Artist(TERUI, "Yoshiruma Terui"),
        Artist(4, "Clairo"),
    )

    val tracks = listOf(
        Track(1, "Our Mission", HIDDEN_INVENTORY),
        Track(2, "The Search For the Cursed Spirit", HIDDEN_INVENTORY),
        Track(3, "A Little Lesson", HIDDEN_INVENTORY),
        Track(4, "Daniel", HIDDEN_INVENTORY),
        Track(5, "No Hesitation", HIDDEN_INVENTORY),
    )

    val albums = listOf(
        Album(HIDDEN_INVENTORY, "Hidden Inventory / Premature Death", TERUI, listOf(1, 2, 3, 4, 5)),
        Album(CULLING_GAMES, "The Culling Games Part 1", TERUI, emptyList()),
        Album(A_LITTLE_LESSON, "A Little Lesson", TERUI, emptyList()),
    )

    val playlists = listOf(
        Playlist(1, "Playlist 1", listOf(5, 1, 3)),
        Playlist(2, "Playlist 2", listOf(2, 4)),
        Playlist(3, "Playlist 3", listOf(1, 2, 3, 4, 5)),
        Playlist(4, "Playlist 4", listOf(4, 5)),
    )

    val library = MusicLibrary(artists, albums, tracks, playlists)

    /** Home list order from 71:1618: playlist, album, then more playlists. */
    val home: List<LibraryItem> = listOf(
        LibraryItem.PlaylistItem(playlists[0]),
        LibraryItem.AlbumItem(albums[0]),
        LibraryItem.PlaylistItem(playlists[1]),
        LibraryItem.PlaylistItem(playlists[2]),
        LibraryItem.PlaylistItem(playlists[3]),
    )

    /** "No Hesitation" is playing in 71:759. */
    val initialQueue: List<Int> = albums[0].trackIds
    val initialQueueIndex: Int = initialQueue.indexOf(5)
}
