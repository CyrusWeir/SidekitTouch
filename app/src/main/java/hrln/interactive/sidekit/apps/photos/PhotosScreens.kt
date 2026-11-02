package hrln.interactive.sidekit.apps.photos

import androidx.compose.foundation.background
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.PublishChrome
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private const val GRID_COLUMNS = 4

/** "Photos main" (36:41). */
@Composable
fun PhotosMainScreen(photos: List<Photo>, onOpenPhoto: (Photo) -> Unit, onAlbums: () -> Unit) {
    PhotoGridScreen(title = "Photos", photos = photos, onOpenPhoto = onOpenPhoto, actions = listOf(SideAction.forward("albums", "Albums", onAlbums)))
}

/** "Recent Albums" (36:220) and "Album 1 selected" (36:286): the same grid titled with the album. */
@Composable
fun AlbumScreen(album: PhotoAlbum, photos: List<Photo>, onOpenPhoto: (Photo) -> Unit, onBack: () -> Unit) {
    PhotoGridScreen(title = album.name, photos = photos, onOpenPhoto = onOpenPhoto, actions = listOf(SideAction.back("back", "Albums", onBack)))
}

/** 4-column grid of 104-high placeholder tiles, faded per row as in the mocks. */
@Composable
private fun PhotoGridScreen(
    title: String,
    photos: List<Photo>,
    onOpenPhoto: (Photo) -> Unit,
    actions: List<SideAction>,
) {
    SidekitScaffold(title = title, actions = actions) { padding ->
        val listState = rememberLazyListState()
        val rows = photos.chunked(GRID_COLUMNS)
        SidekitList(padding, state = listState) {
            itemsIndexed(rows) { index, row ->
                SidekitItemRow(Modifier.listFade(listState, index)) {
                    row.forEach { photo ->
                        SidekitTile(
                            modifier = Modifier.weight(1f),
                            color = SidekitTheme.colors.surfaceA3,
                            onClick = { onOpenPhoto(photo) },
                            onClickLabel = "Open photo ${photo.id}",
                        ) {}
                    }
                    // Keep a short last row on the grid.
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** "Albums" (36:92). The "+" square has no mocked destination, so it's a no-op. */
@Composable
fun AlbumsScreen(
    smartAlbums: List<PhotoAlbum>,
    userAlbums: List<PhotoAlbum>,
    onOpenAlbum: (PhotoAlbum) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    SidekitScaffold(
        title = "Albums",
        actions = listOf(
            SideAction.back("back", "Photos", onBack),
            SideAction.square("add", SidekitIcon.Add, "New album", onAdd),
        ),
    ) { padding ->
        val listState = rememberLazyListState()
        val pairs = smartAlbums.chunked(2)
        SidekitList(padding, state = listState) {
            itemsIndexed(pairs) { index, pair ->
                SidekitItemRow(Modifier.listFade(listState, index)) {
                    pair.forEach { album ->
                        SidekitTile(Modifier.weight(1f), onClick = { onOpenAlbum(album) }) { SidekitText(album.name) }
                    }
                }
            }
            itemsIndexed(userAlbums, key = { _, album -> album.id }) { i, album ->
                AlbumRow(album, Modifier.listFade(listState, pairs.size + i), onClick = { onOpenAlbum(album) })
            }
        }
    }
}

@Composable
private fun AlbumRow(album: PhotoAlbum, modifier: Modifier, onClick: () -> Unit) {
    SidekitTile(modifier.fillMaxWidth(), onClick = onClick, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding), verticalAlignment = Alignment.CenterVertically) {
            SidekitText(album.name, Modifier.weight(1f))
            album.itemCount?.let { SidekitText("$it items") }
        }
    }
}

/** "photo view" (71:2165): the photo is a placeholder surface in the 460 x 498 slot. */
@Composable
fun PhotoViewScreen(photo: Photo, onBack: () -> Unit) {
    val spacing = SidekitTheme.spacing
    Column(Modifier.fillMaxSize().padding(spacing.pagePadding)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(SidekitTheme.shapes.item)
                .background(SidekitTheme.colors.surfaceA2),
        )
        Spacer(Modifier.height(spacing.pageGap + spacing.actionBarHeight))
    }
    PublishChrome(
        header = null,
        actions = listOf(SideAction("back", "Back", SidekitIcon.ArrowLeftAlt, motion = ActionMotion.Back, contentDescription = "Back from photo ${photo.id}", onClick = onBack)),
    )
}

@SidephonePreview
@Composable
private fun PhotosMainPreview() = SidekitPreviewFrame { PhotosMainScreen(PhotosSampleData.photos, {}, {}) }

@SidephonePreview
@Composable
private fun AlbumsPreview() = SidekitPreviewFrame {
    AlbumsScreen(PhotosSampleData.smartAlbums, PhotosSampleData.userAlbums, {}, {}, {})
}

@SidephonePreview
@Composable
private fun RecentAlbumPreview() = SidekitPreviewFrame {
    val album = PhotosSampleData.smartAlbums.first()
    AlbumScreen(album, PhotosSampleData.photosIn(album), {}, {})
}

@SidephonePreview
@Composable
private fun PhotoViewPreview() = SidekitPreviewFrame { PhotoViewScreen(Photo(1), {}) }
