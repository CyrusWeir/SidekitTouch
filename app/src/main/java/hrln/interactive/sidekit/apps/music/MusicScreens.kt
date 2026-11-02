package hrln.interactive.sidekit.apps.music

import androidx.compose.foundation.background
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

/** Callbacks for opening a library row, shared by home, library lists and search. */
class MusicActions(
    val openPlaylist: (Playlist) -> Unit = {},
    val openAlbum: (Album) -> Unit = {},
    val openArtist: (Artist) -> Unit = {},
    val openTrack: (Track) -> Unit = {},
) {
    fun open(item: LibraryItem) = when (item) {
        is LibraryItem.PlaylistItem -> openPlaylist(item.playlist)
        is LibraryItem.AlbumItem -> openAlbum(item.album)
        is LibraryItem.ArtistItem -> openArtist(item.artist)
        is LibraryItem.TrackItem -> openTrack(item.track)
    }
}

/** Music home (71:1618, "Thirds Left" action bar; the 71:1819 variant swaps the bar's emphasis). */
@Composable
fun MusicHomeScreen(
    items: List<LibraryItem>,
    actions: MusicActions,
    onPlaylists: () -> Unit,
    onAlbums: () -> Unit,
    onArtists: () -> Unit,
    onNowPlaying: () -> Unit,
    onSearch: () -> Unit,
) {
    SidekitScaffold(
        title = "Music",
        actions = listOf(
            SideAction("nowPlaying", square = true, contentDescription = "Now Playing", artKey = "nowPlayingArt", art = { AlbumArt(SMALL_ART) }, onClick = onNowPlaying),
            SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch),
        ),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    IconTile(SidekitIcon.QueueMusic, "Playlists", onPlaylists)
                    IconTile(SidekitIcon.Album, "Albums", onAlbums)
                    IconTile(SidekitIcon.Artist, "Artists", onArtists)
                }
            }
            libraryRows(items, listState, firstIndex = 1, actions)
        }
    }
}

/** Playlists / Albums (71:1483) / Artists (71:1729): a faded list of one kind of item. */
@Composable
fun MusicLibraryListScreen(title: String, items: List<LibraryItem>, actions: MusicActions, onBack: () -> Unit) {
    SidekitScaffold(title = title, actions = backToMusic(onBack)) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) { libraryRows(items, listState, firstIndex = 0, actions) }
    }
}

/** "album selected" (71:1051). */
@Composable
fun AlbumScreen(
    album: Album,
    tracks: List<Track>,
    onArtist: () -> Unit,
    onPlay: () -> Unit,
    onTrack: (Track) -> Unit,
    onBack: () -> Unit,
) {
    SidekitScaffold(title = album.title.substringBefore(" / "), actions = backToMusic(onBack)) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    SidekitTile(Modifier.weight(1f)) { AlbumArt(SMALL_ART) }
                    IconTile(SidekitIcon.Artist, "Artist", onArtist)
                    IconTile(SidekitIcon.Bookmark, "Bookmark", {})
                    IconTile(SidekitIcon.PlayArrow, "Play", onPlay, colour = PlayTileColour.Light)
                }
            }
            itemsIndexed(tracks, key = { _, t -> "track${t.id}" }) { index, track ->
                SidekitItem(track.title, Modifier.listFade(listState, index + 1), onClick = { onTrack(track) })
            }
        }
    }
}

/** "artist selected" (71:1426). */
@Composable
fun ArtistScreen(
    artist: Artist,
    albums: List<Album>,
    onPlay: () -> Unit,
    onAlbum: (Album) -> Unit,
    onBack: () -> Unit,
) {
    SidekitScaffold(title = artist.name, actions = backToMusic(onBack)) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    IconTile(SidekitIcon.Bookmark, "Bookmark", {})
                    IconTile(SidekitIcon.PlayArrow, "Play", onPlay, colour = PlayTileColour.Light)
                }
            }
            libraryRows(albums.map(LibraryItem::AlbumItem), listState, firstIndex = 1, MusicActions(openAlbum = onAlbum))
        }
    }
}

/** "now playing" (71:759). */
@Composable
fun NowPlayingScreen(
    track: Track?,
    isPlaying: Boolean,
    onAlbum: () -> Unit,
    onArtist: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val gap = SidekitTheme.spacing.itemOuterGap
    SidekitScaffold(title = "Now Playing", actions = backToMusic(onBack)) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            AlbumArt(LARGE_ART, SidekitTheme.shapes.item, bordered = false)
            SidekitText(track?.title ?: "", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            SidekitItemRow {
                IconTile(SidekitIcon.Album, "Album", onAlbum)
                IconTile(SidekitIcon.Artist, "Artist", onArtist)
                IconTile(SidekitIcon.Bookmark, "Bookmark", {})
            }
            SidekitItemRow {
                IconTile(SidekitIcon.SkipPrevious, "Previous", onPrevious)
                IconTile(
                    if (isPlaying) SidekitIcon.Pause else SidekitIcon.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    onPlayPause,
                    colour = PlayTileColour.Strong,
                )
                IconTile(SidekitIcon.SkipNext, "Next", onNext)
            }
        }
    }
}

/** "Search blank" (71:1929) and "Search 'steve la'" (71:1890): the header is the text field. */
@Composable
fun MusicSearchScreen(
    query: String,
    results: List<LibraryItem>,
    onQueryChange: (String) -> Unit,
    actions: MusicActions,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    SidekitScaffold(
        title = null,
        header = { SearchHeader(query, onQueryChange, focus) },
        actions = backToMusic(onBack),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) { libraryRows(results, listState, firstIndex = 0, actions) }
    }
}

// ---- Private building blocks -------------------------------------------------------------

private val SMALL_ART = 44.dp
private val LARGE_ART = 124.dp

private enum class PlayTileColour { None, Light, Strong }

@Composable
private fun androidx.compose.foundation.layout.RowScope.IconTile(
    icon: SidekitIcon,
    label: String,
    onClick: () -> Unit,
    colour: PlayTileColour = PlayTileColour.None,
) {
    val colors = SidekitTheme.colors
    SidekitIconItem(
        icon = icon,
        contentDescription = label,
        modifier = Modifier.weight(1f),
        color = when (colour) {
            PlayTileColour.None -> colors.surfaceA3
            PlayTileColour.Light -> colors.primaryA2
            PlayTileColour.Strong -> colors.primaryA1
        },
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = onClick,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.libraryRows(
    items: List<LibraryItem>,
    listState: LazyListState,
    firstIndex: Int,
    actions: MusicActions,
) {
    itemsIndexed(items, key = { i, item -> "$i:${item.key()}" }) { index, item ->
        SidekitItem(
            title = item.title(),
            icon = item.icon(),
            modifier = Modifier.listFade(listState, firstIndex + index),
            onClick = { actions.open(item) },
        )
    }
}

private fun LibraryItem.key(): String = when (this) {
    is LibraryItem.PlaylistItem -> "p${playlist.id}"
    is LibraryItem.AlbumItem -> "a${album.id}"
    is LibraryItem.ArtistItem -> "r${artist.id}"
    is LibraryItem.TrackItem -> "t${track.id}"
}

private fun LibraryItem.title(): String = when (this) {
    is LibraryItem.PlaylistItem -> playlist.name
    is LibraryItem.AlbumItem -> album.title
    is LibraryItem.ArtistItem -> artist.name
    is LibraryItem.TrackItem -> track.title
}

private fun LibraryItem.icon(): SidekitIcon = when (this) {
    is LibraryItem.PlaylistItem -> SidekitIcon.QueueMusic
    is LibraryItem.AlbumItem -> SidekitIcon.Album
    is LibraryItem.ArtistItem -> SidekitIcon.Artist
    is LibraryItem.TrackItem -> SidekitIcon.MusicNote2
}

/**
 * Album-art placeholder (Figma image fills can't be exported): a two-tone token-coloured
 * square, framed by a 1px light border at the small size as in the mocks' 44px art frames.
 */
@Composable
private fun AlbumArt(size: Dp, shape: RoundedCornerShape = SidekitTheme.shapes.control, bordered: Boolean = true) {
    val colors = SidekitTheme.colors
    Row(
        Modifier
            .size(size)
            .clip(shape)
            .then(if (bordered) Modifier.border(1.dp, colors.light, shape) else Modifier)
            .semantics { contentDescription = "Album art"; role = Role.Image },
    ) {
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.infoA2))
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.dangerA1))
    }
}

private fun backToMusic(onBack: () -> Unit) = listOf(SideAction.back("back", "Music", onBack))

/** Same geometry as SidekitHeader, with an editable title and a "Search here" placeholder. */
@Composable
private fun SearchHeader(query: String, onQueryChange: (String) -> Unit, focusRequester: FocusRequester) {
    val spacing = SidekitTheme.spacing
    val colors = SidekitTheme.colors
    val style = SidekitTheme.typography.title
    Box(
        Modifier
            .fillMaxWidth()
            .height(spacing.headerHeight)
            .padding(horizontal = spacing.itemPadding)
            .padding(top = spacing.itemPadding),
    ) {
        SidekitTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search music" },
            textStyle = style,
            placeholder = "Search here",
            focusRequester = focusRequester,
            focusOnKeypad = true,
            singleLine = true,
            autoFocus = true,
        )
    }
}

// ---- Previews ----------------------------------------------------------------------------

private val sample = MusicUiState()

@SidephonePreview
@Composable
private fun MusicHomePreview() = SidekitPreviewFrame {
    MusicHomeScreen(sample.home, MusicActions(), {}, {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun AlbumsPreview() = SidekitPreviewFrame {
    MusicLibraryListScreen("Albums", MusicSampleData.albums.map(LibraryItem::AlbumItem), MusicActions(), {})
}

@SidephonePreview
@Composable
private fun ArtistsPreview() = SidekitPreviewFrame {
    MusicLibraryListScreen("Artists", MusicSampleData.artists.map(LibraryItem::ArtistItem), MusicActions(), {})
}

@SidephonePreview
@Composable
private fun AlbumPreview() = SidekitPreviewFrame {
    val album = MusicSampleData.albums.first()
    AlbumScreen(album, sample.library.tracksOf(album).take(4), {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun ArtistPreview() = SidekitPreviewFrame {
    val artist = sample.library.artist(3)!!
    ArtistScreen(artist, sample.library.albumsBy(artist), {}, {}, {})
}

@SidephonePreview
@Composable
private fun NowPlayingPreview() = SidekitPreviewFrame {
    NowPlayingScreen(sample.nowPlaying, isPlaying = true, {}, {}, {}, {}, {}, {})
}

@SidephonePreview
@Composable
private fun SearchBlankPreview() = SidekitPreviewFrame {
    MusicSearchScreen("", emptyList(), {}, MusicActions(), {})
}

@SidephonePreview
@Composable
private fun SearchPreview() = SidekitPreviewFrame {
    MusicSearchScreen("Steve La", sample.library.search("Steve La"), {}, MusicActions(), {})
}
