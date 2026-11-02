package hrln.interactive.sidekit.apps.music

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import hrln.interactive.sidekit.apps.media.changes
import kotlinx.coroutines.flow.Flow

@Immutable
data class Artist(val id: Long, val name: String)

@Immutable
data class Album(val id: Long, val title: String, val artistId: Long, val trackIds: List<Long>, val artist: String = "") {
    /** MediaStore serves album art (embedded or folder.jpg) as this album's thumbnail. */
    val artUri: Uri get() = albumArtUri(id)
}

@Immutable
data class Track(
    val id: Long,
    val title: String,
    val albumId: Long,
    val artistId: Long = 0,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val addedAt: Long = 0,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

@Immutable
data class Playlist(val id: Long, val name: String, val trackIds: List<Long>)

fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)

/** A row in the home list or the search results. */
sealed interface LibraryItem {
    data class PlaylistItem(val playlist: Playlist) : LibraryItem
    data class AlbumItem(val album: Album) : LibraryItem
    data class ArtistItem(val artist: Artist) : LibraryItem
    data class TrackItem(val track: Track) : LibraryItem
}

@Immutable
class MusicLibrary(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
    val playlists: List<Playlist>,
) {
    private val trackById = tracks.associateBy { it.id }
    private val albumById = albums.associateBy { it.id }
    private val artistById = artists.associateBy { it.id }

    fun artist(id: Long): Artist? = artistById[id]
    fun album(id: Long): Album? = albumById[id]
    fun track(id: Long): Track? = trackById[id]
    fun playlist(id: Long): Playlist? = playlists.firstOrNull { it.id == id }
    fun tracksOf(album: Album): List<Track> = album.trackIds.mapNotNull(::track)
    fun tracksOf(playlist: Playlist): List<Track> = playlist.trackIds.mapNotNull(::track)
    fun albumsBy(artist: Artist): List<Album> = albums.filter { it.artistId == artist.id }
    fun tracksBy(artist: Artist): List<Track> = albumsBy(artist).flatMap(::tracksOf)
    fun artistOf(album: Album): Artist? = artist(album.artistId)

    fun withPlaylists(playlists: List<Playlist>) = MusicLibrary(artists, albums, tracks, playlists)

    /**
     * Case-insensitive substring search over artists, albums, tracks and playlists (in that
     * order), capped so a one-letter query doesn't build thousands of rows. A blank query
     * returns nothing ("Search blank", 71:1929).
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<LibraryItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        fun String.matches() = contains(q, ignoreCase = true)
        return sequence {
            artists.forEach { if (it.name.matches()) yield(LibraryItem.ArtistItem(it)) }
            albums.forEach { if (it.title.matches()) yield(LibraryItem.AlbumItem(it)) }
            tracks.forEach { if (it.title.matches()) yield(LibraryItem.TrackItem(it)) }
            playlists.forEach { if (it.name.matches()) yield(LibraryItem.PlaylistItem(it)) }
        }.take(limit).toList()
    }

    /** Home list: your playlists, then the most recently added albums. */
    fun home(maxAlbums: Int = HOME_ALBUMS): List<LibraryItem> {
        val added = HashMap<Long, Long>()
        tracks.forEach { t -> added[t.albumId] = maxOf(added[t.albumId] ?: 0L, t.addedAt) }
        return playlists.map(LibraryItem::PlaylistItem) +
            albums.sortedByDescending { added[it.id] ?: 0L }.take(maxAlbums).map(LibraryItem::AlbumItem)
    }

    companion object {
        const val SEARCH_LIMIT = 100
        const val HOME_ALBUMS = 12
        val Empty = MusicLibrary(emptyList(), emptyList(), emptyList(), emptyList())

        /**
         * Builds albums and artists from track rows. Albums keep disc order (track number, then
         * title); albums and artists are sorted by name, ignoring case and a leading "The".
         */
        fun from(tracks: List<Track>, playlists: List<Playlist> = emptyList()): MusicLibrary {
            val byAlbum = tracks.groupBy { it.albumId }
            val albums = byAlbum.map { (id, list) ->
                val sorted = list.sortedWith(compareBy<Track> { it.trackNumber }.thenBy { it.title.lowercase() })
                // An album's artist is the one on most of its tracks (compilations get "Various").
                val artists = sorted.groupingBy { it.artistId }.eachCount()
                val main = artists.maxByOrNull { it.value }?.key ?: 0L
                val name = if (artists.size > 1 && artists.values.max() * 2 <= sorted.size) "Various Artists" else sorted.first { it.artistId == main }.artist
                Album(id, sorted.first().album.ifBlank { "Unknown album" }, main, sorted.map { it.id }, name)
            }.sortedBy { sortKey(it.title) }
            val artists = tracks.distinctBy { it.artistId }
                .map { Artist(it.artistId, it.artist.ifBlank { "Unknown artist" }) }
                .sortedBy { sortKey(it.name) }
            val sortedTracks = tracks.sortedBy { sortKey(it.title) }
            return MusicLibrary(artists, albums, sortedTracks, playlists)
        }

        private fun sortKey(s: String) = s.lowercase().removePrefix("the ").trim()
    }
}

/** Reads the music on the phone. Called off the main thread only. */
interface MusicSource {
    fun tracks(): List<Track>
    fun changes(): Flow<Unit>
}

class MediaStoreMusicSource(private val resolver: ContentResolver) : MusicSource {
    override fun tracks(): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        return resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            val out = ArrayList<Track>(c.count)
            while (c.moveToNext()) {
                out += Track(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty().ifBlank { "Untitled" },
                    albumId = c.getLong(2),
                    artistId = c.getLong(3),
                    artist = c.getString(4).takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                    album = c.getString(5).orEmpty(),
                    durationMs = c.getLong(6),
                    // TRACK is disc * 1000 + track.
                    trackNumber = c.getInt(7),
                    addedAt = c.getLong(8),
                )
            }
            out
        }.orEmpty()
    }

    override fun changes(): Flow<Unit> = resolver.changes(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
}
