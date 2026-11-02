package hrln.interactive.sidekit.apps.photos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.apps.media.MediaPermissions
import hrln.interactive.sidekit.apps.media.PermissionState
import hrln.interactive.sidekit.apps.media.rememberPermissionState
import hrln.interactive.sidekit.apps.video.VideoRoutes
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Photos.route
private val MAIN = "$ROOT/main"
private val ALBUMS = "$ROOT/albums"
private val ALBUM = "$ROOT/album/{album}?name={name}"
private val VIEW = "$ROOT/view/{album}/{id}"

private fun albumRoute(album: PhotoAlbum) = "$ROOT/album/${album.key.route}?name=${Uri.encode(album.name)}"

/** The viewer on [id], paging through [album]. Other apps (Camera) open captures with this. */
fun photosViewerRoute(album: AlbumKey, id: Long) = "$ROOT/view/${album.route}/$id"

fun NavGraphBuilder.photosGraph(nav: NavHostController) {
    navigation(startDestination = MAIN, route = ROOT) {
        composable(MAIN) { entry ->
            val vm = entry.photosViewModel(nav)
            val permission = rememberPhotosPermission()
            val access = permission.toAccess()
            val grid by (if (permission.granted) vm.grid(AlbumKey.All) else NotGranted).collectAsStateWithLifecycle()
            PhotosMainScreen(
                grid = grid,
                onOpen = { nav.navigateFrom(entry, photosViewerRoute(AlbumKey.All, it.id)) },
                onAlbums = { nav.navigateFrom(entry, ALBUMS) },
                onLoadMore = { vm.loadMore(AlbumKey.All) },
                access = access,
            )
        }
        composable(ALBUMS) { entry ->
            val vm = entry.photosViewModel(nav)
            val permission = rememberPhotosPermission()
            val albums by (if (permission.granted) vm.albums else NoAlbums).collectAsStateWithLifecycle()
            AlbumsScreen(
                smartAlbums = smartAlbums,
                folders = albums.folders,
                onOpenAlbum = { nav.navigateFrom(entry, albumRoute(it)) },
                onBack = { nav.popFrom(entry) },
            )
        }
        composable(
            ALBUM,
            arguments = listOf(
                navArgument("album") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val vm = entry.photosViewModel(nav)
            val album = remember(entry) { AlbumKey.parse(entry.arguments?.getString("album")) }
            val name = entry.arguments?.getString("name") ?: album.smartName() ?: "Album"
            val permission = rememberPhotosPermission()
            val grid by (if (permission.granted) vm.grid(album) else NotGranted).collectAsStateWithLifecycle()
            AlbumScreen(
                title = name,
                grid = grid,
                onOpen = { nav.navigateFrom(entry, photosViewerRoute(album, it.id)) },
                onBack = { nav.popFrom(entry) },
                onLoadMore = { vm.loadMore(album) },
                access = permission.toAccess(),
            )
        }
        composable(
            VIEW,
            arguments = listOf(navArgument("album") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val vm = entry.photosViewModel(nav)
            val album = remember(entry) { AlbumKey.parse(entry.arguments?.getString("album")) }
            val startId = entry.arguments?.getLong("id") ?: -1L
            val context = LocalContext.current
            val grid by vm.grid(album).collectAsStateWithLifecycle()
            val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
            // The index is fixed when the grid first has the photo; afterwards the pager owns the position.
            var start by remember(entry) { mutableStateOf<Int?>(null) }
            if (start == null && grid.loaded) start = grid.items.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            var deleting by remember { mutableStateOf<MediaItem?>(null) }
            val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val item = deleting
                deleting = null
                if (result.resultCode == Activity.RESULT_OK && item != null) vm.onDeleted(item)
            }
            val startIndex = start ?: return@composable
            PhotoViewScreen(
                items = grid.items,
                startIndex = startIndex,
                favourites = favourites,
                onBack = { nav.popFrom(entry) },
                onToggleFavourite = vm::toggleFavourite,
                onDelete = { item ->
                    // Android asks once more; apps may not delete others' media silently.
                    val sender = runCatching { MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri)).intentSender }.getOrNull()
                    if (sender != null) {
                        deleting = item
                        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                },
                onShare = { share(context, it) },
                onPlayVideo = { nav.navigateFrom(entry, VideoRoutes.player(it.id)) },
                onLoadMore = { vm.loadMore(album) },
            )
        }
    }
}

private val NotGranted = kotlinx.coroutines.flow.MutableStateFlow(GridState())
private val NoAlbums = kotlinx.coroutines.flow.MutableStateFlow(AlbumsState())

@Composable
private fun rememberPhotosPermission(): PermissionState = rememberPermissionState(MediaPermissions.images)

private fun PermissionState.toAccess() = PhotosAccess(access, ::request)

private fun share(context: Context, item: MediaItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(item.mimeType)
        .putExtra(Intent.EXTRA_STREAM, item.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/** Ignores repeat taps that land while a transition is already under way. */
private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id == entry.id) navigate(route) { launchSingleTop = true }
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** One ViewModel per visit to Photos, shared by its screens; favourites outlive it. */
@Composable
private fun NavBackStackEntry.photosViewModel(nav: NavHostController): PhotosViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(
        parent,
        factory = remember(context) {
            viewModelFactory { initializer { PhotosViewModel(MediaStorePhotoSource(context.contentResolver), Photos.favourites(context)) } }
        },
    )
}
