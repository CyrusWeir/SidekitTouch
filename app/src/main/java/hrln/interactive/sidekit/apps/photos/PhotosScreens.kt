package hrln.interactive.sidekit.apps.photos

import android.content.Context
import android.graphics.ImageDecoder
import android.util.Size
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.apps.media.Access
import hrln.interactive.sidekit.apps.media.PermissionRationale
import hrln.interactive.sidekit.apps.media.Thumbnail
import hrln.interactive.sidekit.apps.media.formatDuration
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.PublishChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PhotosTags {
    const val GRID = "photos_grid"
    const val VIEWER = "photos_viewer"
}

private const val GRID_COLUMNS = 4

/** Tile size in the 460-wide grid (four columns, three 3 px gaps); thumbnails are cut to it. */
private val TILE_WIDTH = 113.dp

/** Load the next page once the grid is within this many rows of the end. */
private const val PREFETCH_ROWS = 6

/** Shown instead of a grid until Photos may read the library. */
class PhotosAccess(val access: Access = Access.Granted, val onAllow: () -> Unit = {}) {
    val granted: Boolean get() = access == Access.Granted
}

/** "Photos main" (36:41): every photo and video, newest first. */
@Composable
fun PhotosMainScreen(
    grid: GridState,
    onOpen: (MediaItem) -> Unit,
    onAlbums: () -> Unit,
    onLoadMore: () -> Unit = {},
    access: PhotosAccess = PhotosAccess(),
) {
    PhotoGridScreen(
        title = "Photos",
        grid = grid,
        onOpen = onOpen,
        onLoadMore = onLoadMore,
        access = access,
        actions = listOf(SideAction.forward("albums", "Albums", onAlbums)),
        empty = "No photos yet",
    )
}

/** "Recent Albums" (36:220) and "Album 1 selected" (36:286): the same grid titled with the album. */
@Composable
fun AlbumScreen(
    title: String,
    grid: GridState,
    onOpen: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit = {},
    access: PhotosAccess = PhotosAccess(),
) {
    PhotoGridScreen(
        title = title,
        grid = grid,
        onOpen = onOpen,
        onLoadMore = onLoadMore,
        access = access,
        actions = listOf(SideAction.back("back", "Albums", onBack)),
        empty = if (title == "Favourites") "Tap the heart on a photo to add it here" else "Nothing in this album",
    )
}

/** 4-column grid of 104-high tiles, faded per row as in the mocks. Thumbnails load as rows appear. */
@Composable
private fun PhotoGridScreen(
    title: String,
    grid: GridState,
    onOpen: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
    access: PhotosAccess,
    actions: List<SideAction>,
    empty: String,
) {
    if (!access.granted) {
        SidekitScaffold(title = title, actions = listOf(allowPhotos(access))) { padding ->
            PermissionRationale("See your photos", "Photos shows the pictures and videos on this phone. Nothing leaves the device.", access.access, padding)
        }
        return
    }
    SidekitScaffold(title = title, actions = actions) { padding ->
        if (grid.loaded && grid.items.isEmpty()) {
            EmptyBody(empty, padding)
            return@SidekitScaffold
        }
        val listState = rememberLazyListState()
        val rows = remember(grid.items) { grid.items.chunked(GRID_COLUMNS) }
        val loadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(listState, grid.endReached) {
            if (grid.endReached) return@LaunchedEffect
            snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - PREFETCH_ROWS
            }.distinctUntilChanged().filter { it }.collect { loadMore() }
        }
        SidekitList(padding, Modifier.testTag(PhotosTags.GRID), state = listState) {
            itemsIndexed(rows, key = { _, row -> row.first().id }, contentType = { _, _ -> 0 }) { index, row ->
                SidekitItemRow(Modifier.listFade(listState, index)) {
                    row.forEach { item -> PhotoTile(item, Modifier.weight(1f), onClick = { onOpen(item) }) }
                    // Keep a short last row on the grid.
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(item: MediaItem, modifier: Modifier, onClick: () -> Unit) {
    val height = SidekitTheme.spacing.itemHeight
    SidekitTile(
        modifier = modifier,
        color = SidekitTheme.colors.surfaceA3,
        onClick = onClick,
        onClickLabel = if (item.isVideo) "Open video" else "Open photo",
    ) {
        Thumbnail(item.uri, TILE_WIDTH, height, Modifier.fillMaxSize())
        if (item.isVideo) {
            SidekitText(
                formatDuration(item.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                style = SidekitTheme.typography.label,
            )
        }
    }
}

@Composable
private fun EmptyBody(text: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        SidekitText(text, style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, textAlign = TextAlign.Center, maxLines = 3)
    }
}

private fun allowPhotos(access: PhotosAccess) =
    if (access.access == Access.Blocked) SideAction.forward("allow", "Open settings", access.onAllow, icon = SidekitIcon.Settings)
    else SideAction("allow", "Allow", SidekitIcon.Check, iconAtEnd = true, tone = ActionTone.Secondary, contentDescription = "Allow access to photos", onClick = access.onAllow)

/**
 * "Albums" (36:92): the four smart albums as two-up tiles, then the phone's folders with their
 * item counts. The mock's "+" had no destination (MediaStore albums are folders, made by saving
 * into them), so the bar is just the way back.
 */
@Composable
fun AlbumsScreen(
    smartAlbums: List<PhotoAlbum>,
    folders: List<PhotoAlbum>,
    onOpenAlbum: (PhotoAlbum) -> Unit,
    onBack: () -> Unit,
) {
    SidekitScaffold(title = "Albums", actions = listOf(SideAction.back("back", "Photos", onBack))) { padding ->
        val listState = rememberLazyListState()
        val pairs = smartAlbums.chunked(2)
        SidekitList(padding, state = listState) {
            itemsIndexed(pairs, key = { _, pair -> "smart:" + pair.first().key.route }) { index, pair ->
                SidekitItemRow(Modifier.listFade(listState, index)) {
                    pair.forEach { album ->
                        SidekitTile(Modifier.weight(1f), onClick = { onOpenAlbum(album) }) { SidekitText(album.name) }
                    }
                }
            }
            itemsIndexed(folders, key = { _, album -> album.key.route }) { i, album ->
                AlbumRow(album, Modifier.listFade(listState, pairs.size + i), onClick = { onOpenAlbum(album) })
            }
        }
    }
}

@Composable
private fun AlbumRow(album: PhotoAlbum, modifier: Modifier, onClick: () -> Unit) {
    SidekitTile(modifier.fillMaxWidth(), onClick = onClick, contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
        ) {
            SidekitText(album.name, Modifier.weight(1f))
            album.itemCount?.let { SidekitText(if (it == 1) "1 item" else "$it items") }
        }
    }
}

private enum class ViewerMode { Normal, ConfirmDelete }

/**
 * "photo view" (71:2165): the photo fills the 460 x 498 slot, decoded at that size. Swipe (or
 * keypad 4/6, left/right) for the next and previous item in the grid it was opened from.
 *
 * Bar: "← Back", a heart square (filled blue when a favourite) and a red delete square. Delete
 * turns the bar into "Cancel | Delete"; confirming hands over to Android's own delete prompt
 * (`createDeleteRequest`), which apps can't skip. Share is under Options. A video shows its
 * poster frame; tapping it plays it in Video.
 */
@Composable
fun PhotoViewScreen(
    items: List<MediaItem>,
    startIndex: Int,
    favourites: Set<Long>,
    onBack: () -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onPlayVideo: (MediaItem) -> Unit,
    onLoadMore: () -> Unit = {},
) {
    val spacing = SidekitTheme.spacing
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(ViewerMode.Normal) }
    val current = items.getOrNull(pager.currentPage)
    val focus = remember { FocusRequester() }
    val loadMore by rememberUpdatedState(onLoadMore)

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage >= items.size - 3 }.filter { it }.collect { loadMore() }
    }
    LaunchedEffect(pager.currentPage) { mode = ViewerMode.Normal }
    // Deleting the last photo of an album leaves nothing to show.
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onBack() }

    BackHandler(enabled = mode == ViewerMode.ConfirmDelete) { mode = ViewerMode.Normal }

    fun step(delta: Int) {
        val target = (pager.currentPage + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        scope.launch { pager.animateScrollToPage(target) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(spacing.pagePadding)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_4 -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_6 -> { step(1); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(PhotosTags.VIEWER),
            pageSpacing = spacing.pageGap,
            key = { items.getOrNull(it)?.id ?: it },
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            FullPhoto(item, onPlayVideo = { onPlayVideo(item) })
        }
        Spacer(Modifier.height(spacing.pageGap + spacing.actionBarHeight))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val favourite = current != null && current.id in favourites
    val actions = when {
        current == null -> listOf(SideAction.back("back", "Back", onBack))
        mode == ViewerMode.ConfirmDelete -> listOf(
            SideAction("back", "Cancel", tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Keep ${kind(current)}") { mode = ViewerMode.Normal },
            SideAction("delete", "Delete", SidekitIcon.DeleteForever, tone = ActionTone.Destructive, weight = 1.2f, motion = ActionMotion.Up, contentDescription = "Confirm delete") {
                mode = ViewerMode.Normal
                onDelete(current)
            },
        )
        else -> listOf(
            SideAction("back", "Back", SidekitIcon.ArrowLeftAlt, motion = ActionMotion.Back, contentDescription = "Back from ${kind(current)}", onClick = onBack),
            SideAction.square(
                "favourite",
                SidekitIcon.Favorite,
                if (favourite) "Remove from Favourites" else "Add to Favourites",
                { onToggleFavourite(current) },
                tone = if (favourite) ActionTone.Primary else ActionTone.Muted,
            ),
            SideAction.square("delete", SidekitIcon.DeleteForever, "Delete ${kind(current)}", { mode = ViewerMode.ConfirmDelete }, tone = ActionTone.Destructive),
        )
    }
    val options = if (current == null || mode == ViewerMode.ConfirmDelete) emptyList() else listOfNotNull(
        SideAction("share", "Share", SidekitIcon.ArrowUpward) { onShare(current) },
        SideAction("favourite", if (favourite) "Unfavourite" else "Favourite", SidekitIcon.Favorite) { onToggleFavourite(current) },
        if (current.isVideo) SideAction("play", "Play", SidekitIcon.PlayArrow) { onPlayVideo(current) } else null,
    )
    PublishChrome(header = null, actions = actions, options = options)
}

private fun kind(item: MediaItem) = if (item.isVideo) "video" else "photo"

/** One page of the viewer: the photo decoded to fit the slot, or a video's poster with a play button. */
@Composable
private fun FullPhoto(item: MediaItem, onPlayVideo: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(SidekitTheme.shapes.item)
            .background(SidekitTheme.colors.surfaceA2)
            .then(if (item.isVideo) Modifier.clickable(onClickLabel = "Play video", role = Role.Button, onClick = onPlayVideo) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalContext.current.applicationContext
        val density = LocalDensity.current
        val w = with(density) { maxWidth.roundToPx() }
        val h = with(density) { maxHeight.roundToPx() }
        val inspection = LocalInspectionMode.current
        var image by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
        if (!inspection) {
            LaunchedEffect(item.id, w, h) {
                image = withContext(Dispatchers.IO) { decodeToFit(context, item, w, h) }?.asImageBitmap()
            }
        }
        image?.let {
            Image(it, contentDescription = if (item.isVideo) "Video" else "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (item.isVideo) {
            Box(
                Modifier.size(SidekitTheme.spacing.itemHeight).clip(SidekitTheme.shapes.item).background(SidekitTheme.colors.primaryA1),
                contentAlignment = Alignment.Center,
            ) { SidekitIconImage(SidekitIcon.PlayArrow, size = SidekitTheme.spacing.tileIconSize) }
        }
    }
}

/**
 * Decodes [item] no larger than [w] x [h] (the screen slot), never the full-resolution original:
 * a 13 MP photo becomes a ~1 MB bitmap. EXIF rotation is applied by [ImageDecoder].
 */
private fun decodeToFit(context: Context, item: MediaItem, w: Int, h: Int): android.graphics.Bitmap? = runCatching {
    if (w <= 0 || h <= 0) return null
    if (item.isVideo) return context.contentResolver.loadThumbnail(item.uri, Size(w, h), null)
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { decoder, info, _ ->
        val scale = minOf(w / info.size.width.toFloat(), h / info.size.height.toFloat(), 1f)
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
    }
}.getOrNull()

// ---- Previews ----------------------------------------------------------------------------

object PhotosSampleData {
    /** Photos main (36:41) shows five rows of four; a sixth row lets the grid scroll. */
    val grid = GridState((1L..24L).map { MediaItem(it) }, loaded = true, endReached = true)

    val folders = listOf(
        PhotoAlbum(AlbumKey.Bucket(5), "Album 1", 50),
        PhotoAlbum(AlbumKey.Bucket(6), "Album 2", 12),
        PhotoAlbum(AlbumKey.Bucket(7), "Album 3", 12),
    )
}

@SidephonePreview
@Composable
private fun PhotosMainPreview() = SidekitPreviewFrame { PhotosMainScreen(PhotosSampleData.grid, {}, {}) }

@SidephonePreview
@Composable
private fun AlbumsPreview() = SidekitPreviewFrame { AlbumsScreen(smartAlbums, PhotosSampleData.folders, {}, {}) }

@SidephonePreview
@Composable
private fun PhotosLockedPreview() = SidekitPreviewFrame {
    PhotosMainScreen(GridState(), {}, {}, access = PhotosAccess(Access.Ask))
}

@SidephonePreview
@Composable
private fun PhotoViewPreview() = SidekitPreviewFrame {
    PhotoViewScreen(PhotosSampleData.grid.items, 0, emptySet(), {}, {}, {}, {}, {})
}
