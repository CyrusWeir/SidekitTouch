package hrln.interactive.sidekit.apps.photos

/** A placeholder photo; the mocks' photos can't be exported, so tiles are token-coloured surfaces. */
data class Photo(val id: Int)

/** @property itemCount shown on user-album rows ("50 items"); null for the smart albums. */
data class PhotoAlbum(val id: Int, val name: String, val itemCount: Int? = null)

/** Placeholder content copied from the Photos mocks. */
object PhotosSampleData {
    /** Photos main (36:41) shows five rows of four; a sixth row lets the grid scroll. */
    val photos: List<Photo> = (1..24).map(::Photo)

    /** The two-up tiles at the top of Albums (36:92). */
    val smartAlbums = listOf(
        PhotoAlbum(1, "Recent"),
        PhotoAlbum(2, "Favourites"),
        PhotoAlbum(3, "Videos"),
        PhotoAlbum(4, "Photos"),
    )

    /** Album rows in Albums (36:92); the mock repeats "Album 2". */
    val userAlbums = listOf(
        PhotoAlbum(5, "Album 1", 50),
        PhotoAlbum(6, "Album 2", 12),
        PhotoAlbum(7, "Album 2", 12),
    )

    fun album(id: Int): PhotoAlbum? = (smartAlbums + userAlbums).firstOrNull { it.id == id }

    /** Photos shown inside an album ("Recent Albums" 36:220, "Album 1 selected" 36:286). */
    fun photosIn(album: PhotoAlbum): List<Photo> = album.itemCount?.let { n -> (1..n).map(::Photo) } ?: photos
}
