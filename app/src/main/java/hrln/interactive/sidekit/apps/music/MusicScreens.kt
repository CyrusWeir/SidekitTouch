package hrln.interactive.sidekit.apps.music

import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.apps.media.Access
import hrln.interactive.sidekit.apps.media.PermissionRationale
import hrln.interactive.sidekit.apps.media.Thumbnail
import hrln.interactive.sidekit.apps.media.formatDuration
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview
import kotlinx.coroutines.delay

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

@Composable
fun MusicHomeScreen(
    items: List<LibraryItem>,
    actions: MusicActions,
    onPlaylists: () -> Unit,
    onAlbums: () -> Unit,
    onArtists: () -> Unit,
    onNowPlaying: () -> Unit,
    onSearch: () -> Unit,
    artUri: Uri? = null,
    artId: Long = 0,
    onNewPlaylist: () -> Unit = {},
) {
    SidekitScaffold(
        title = "Music",
        actions = listOf(
            SideAction("nowPlaying", square = true, contentDescription = "Now Playing", artKey = artId, art = { AlbumArt(SMALL_ART, artUri) }, onClick = onNowPlaying),
            SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch),
        ),
        options = listOf(SideAction("new", "New playlist", SidekitIcon.Add, onClick = onNewPlaylist)),
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
            libraryRows(items, listState, 1, actions)
        }
    }
}

@Composable
fun MusicLibraryListScreen(
    title: String,
    items: List<LibraryItem>,
    actions: MusicActions,
    onBack: () -> Unit,
    onNewPlaylist: (() -> Unit)? = null,
) {
    SidekitScaffold(
        title = title,
        actions = backToMusic(onBack),
        options = onNewPlaylist?.let { listOf(SideAction("new", "New playlist", SidekitIcon.Add, onClick = it)) }.orEmpty(),
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) { libraryRows(items, listState, 0, actions) }
    }
}

@Composable
fun AlbumScreen(album: Album, tracks: List<Track>, onArtist: () -> Unit, onPlay: () -> Unit, onTrack: (Track) -> Unit, onBookmark: () -> Unit, onBack: () -> Unit) {
    SidekitScaffold(title = album.title, actions = backToMusic(onBack)) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    SidekitTile(Modifier.weight(1f)) { AlbumArt(SMALL_ART, album.artUri) }
                    IconTile(SidekitIcon.Artist, "Artist", onArtist)
                    IconTile(SidekitIcon.Bookmark, "Save", onBookmark)
                    IconTile(SidekitIcon.PlayArrow, "Play", onPlay, PlayTileColour.Light)
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                SidekitItem(track.title, Modifier.listFade(listState, index + 1), secondary = formatDuration(track.durationMs), onClick = { onTrack(track) })
            }
        }
    }
}

@Composable
fun ArtistScreen(artist: Artist, albums: List<Album>, onPlay: () -> Unit, onAlbum: (Album) -> Unit, onBack: () -> Unit) {
    SidekitScaffold(title = artist.name, actions = backToMusic(onBack)) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) { IconTile(SidekitIcon.PlayArrow, "Play", onPlay, PlayTileColour.Light) }
            }
            libraryRows(albums.map(LibraryItem::AlbumItem), listState, 1, MusicActions(openAlbum = onAlbum))
        }
    }
}

@Composable
fun NowPlayingScreen(
    playback: PlaybackState,
    onAlbum: () -> Unit,
    onArtist: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onBookmark: () -> Unit,
    onBack: () -> Unit,
) {
    val track = playback.track
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playback.isPlaying) {
        while (playback.isPlaying) { tick++; delay(500) }
    }
    val position = playback.positionAt(SystemClock.elapsedRealtime() + tick * 0)
    val gap = SidekitTheme.spacing.itemOuterGap
    // Back plus the track's destinations live in the action bar, so the body has room for the art
    // and one row of full-size playback controls.
    val actions = remember(onBack, onAlbum, onArtist, onBookmark) {
        listOf(
            SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back to Music", onBack, motion = ActionMotion.Back),
            SideAction("album", icon = SidekitIcon.Album, contentDescription = "Album", onClick = onAlbum),
            SideAction("artist", icon = SidekitIcon.Artist, contentDescription = "Artist", onClick = onArtist),
            SideAction("save", icon = SidekitIcon.Bookmark, contentDescription = "Save", onClick = onBookmark),
        )
    }
    val shuffle = playback.queue.shuffle
    val repeat = playback.queue.repeat
    val options = remember(shuffle, repeat, onShuffle, onRepeat) {
        listOf(
            SideAction("shuffle", if (shuffle) "Shuffle: on" else "Shuffle: off", SidekitIcon.Shuffle, onClick = onShuffle),
            SideAction("repeat", "Repeat: ${repeat.name.lowercase()}", SidekitIcon.Undo, onClick = onRepeat),
        )
    }
    val artUri = remember(track?.albumId) { track?.let { albumArtUri(it.albumId) } }
    SidekitScaffold(title = "Now Playing", actions = actions, options = options) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            // The art takes whatever height the text, seek row and controls leave, as a square.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AlbumArt(minOf(maxWidth, maxHeight), artUri, SidekitTheme.shapes.item, false)
            }
            SidekitText(track?.title ?: "Nothing playing", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2)
            SidekitText(track?.artist.orEmpty(), style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, maxLines = 1)
            MusicSeek(position, playback.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L, onSeek)
            SidekitItemRow {
                IconTile(SidekitIcon.SkipPrevious, "Previous", onPrevious)
                IconTile(if (playback.isPlaying) SidekitIcon.Pause else SidekitIcon.PlayArrow, if (playback.isPlaying) "Pause" else "Play", onPlayPause, PlayTileColour.Strong)
                IconTile(SidekitIcon.SkipNext, "Next", onNext)
            }
        }
    }
}

@Composable
private fun MusicSeek(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val fraction = if (duration > 0) position / duration.toFloat() else 0f
    val trackColor = SidekitTheme.colors.surfaceA4
    val progressColor = SidekitTheme.colors.light
    // The bar takes whatever width the two labels leave, so the elapsed label must not change width
    // as it counts up: otherwise the bar resizes each second and its fill can appear to slip back.
    // Tabular digits make every digit the same width, and the slot is sized for the longest time
    // this track can show (its duration's shape, e.g. "00:00" for a 12-minute track).
    val labelStyle = SidekitTheme.typography.label
    val timeStyle = remember(labelStyle) { labelStyle.copy(fontFeatureSettings = "tnum") }
    val widest = remember(duration) { formatDuration(duration).replace(DIGIT, "0") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(contentAlignment = Alignment.CenterEnd) {
            SidekitText(widest, Modifier.alpha(0f).clearAndSetSemantics {}, style = timeStyle)
            SidekitText(formatDuration(position), style = timeStyle)
        }
        Box(Modifier.weight(1f).height(36.dp).pointerInput(duration) {
            detectHorizontalDragGestures { change, _ -> if (duration > 0) onSeek(((change.position.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
        }.drawBehind {
            val h = 6.dp.toPx(); val y = (size.height - h) / 2
            drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(progressColor, Offset(0f, y), Size(size.width * fraction.coerceIn(0f, 1f), h), CornerRadius(h / 2))
        })
        SidekitText(formatDuration(duration), style = timeStyle)
    }
}

private val DIGIT = Regex("\\d")

@Composable
fun MusicSearchScreen(query: String, results: List<LibraryItem>, onQueryChange: (String) -> Unit, actions: MusicActions, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    SidekitScaffold(title = null, header = { SearchHeader(query, onQueryChange, focus) }, actions = backToMusic(onBack)) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) { libraryRows(results, listState, 0, actions) }
    }
}

@Composable
fun MusicPermissionScreen(access: Access, onBack: () -> Unit) {
    SidekitScaffold(title = "Music", actions = backToMusic(onBack)) { padding ->
        PermissionRationale("Play your music", "Music reads the songs saved on this phone. Playback and your playlists stay on the device.", access, padding)
    }
}

private val SMALL_ART = 44.dp
private enum class PlayTileColour { None, Light, Strong }

@Composable
private fun RowScope.IconTile(icon: SidekitIcon, label: String, onClick: () -> Unit, colour: PlayTileColour = PlayTileColour.None) {
    val colors = SidekitTheme.colors
    SidekitIconItem(icon, label, Modifier.weight(1f), color = when (colour) {
        PlayTileColour.None -> colors.surfaceA3
        PlayTileColour.Light -> colors.primaryA2
        PlayTileColour.Strong -> colors.primaryA1
    }, iconSize = SidekitTheme.spacing.tileIconSize, onClick = onClick)
}

private fun LazyListScope.libraryRows(items: List<LibraryItem>, listState: LazyListState, firstIndex: Int, actions: MusicActions) {
    itemsIndexed(items, key = { i, item -> "$i:${item.key()}" }) { index, item ->
        SidekitItem(item.title(), Modifier.listFade(listState, firstIndex + index), icon = item.icon(), onClick = { actions.open(item) })
    }
}

private fun LibraryItem.key() = when (this) {
    is LibraryItem.PlaylistItem -> "p${playlist.id}"
    is LibraryItem.AlbumItem -> "a${album.id}"
    is LibraryItem.ArtistItem -> "r${artist.id}"
    is LibraryItem.TrackItem -> "t${track.id}"
}
private fun LibraryItem.title() = when (this) {
    is LibraryItem.PlaylistItem -> playlist.name
    is LibraryItem.AlbumItem -> album.title
    is LibraryItem.ArtistItem -> artist.name
    is LibraryItem.TrackItem -> track.title
}
private fun LibraryItem.icon() = when (this) {
    is LibraryItem.PlaylistItem -> SidekitIcon.QueueMusic
    is LibraryItem.AlbumItem -> SidekitIcon.Album
    is LibraryItem.ArtistItem -> SidekitIcon.Artist
    is LibraryItem.TrackItem -> SidekitIcon.MusicNote2
}

@Composable
private fun AlbumArt(size: Dp, uri: Uri? = null, shape: RoundedCornerShape = SidekitTheme.shapes.control, bordered: Boolean = true) {
    Box(Modifier.size(size).clip(shape).background(SidekitTheme.colors.surfaceA2).then(if (bordered) Modifier.border(1.dp, SidekitTheme.colors.light, shape) else Modifier).semantics { contentDescription = "Album art"; role = Role.Image }) {
        Thumbnail(uri, size, size, Modifier.fillMaxSize())
    }
}

private fun backToMusic(onBack: () -> Unit) = listOf(SideAction.back("back", "Music", onBack))

@Composable
private fun SearchHeader(query: String, onQueryChange: (String) -> Unit, focusRequester: FocusRequester) {
    val spacing = SidekitTheme.spacing
    Box(Modifier.fillMaxWidth().height(spacing.headerHeight).padding(horizontal = spacing.itemPadding).padding(top = spacing.headerPaddingTop)) {
        SidekitTextField(query, onQueryChange, Modifier.fillMaxWidth().semantics { contentDescription = "Search music" }, textStyle = SidekitTheme.typography.title, placeholder = "Search here", focusRequester = focusRequester, focusOnKeypad = true, singleLine = true, autoFocus = true)
    }
}

@SidephonePreview
@Composable
private fun MusicHomePreview() = SidekitPreviewFrame { MusicHomeScreen(emptyList(), MusicActions(), {}, {}, {}, {}, {}) }
