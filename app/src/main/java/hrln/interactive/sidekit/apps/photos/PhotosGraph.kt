package hrln.interactive.sidekit.apps.photos

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Photos.route
private val MAIN = "$ROOT/main"
private val ALBUMS = "$ROOT/albums"
private val ALBUM = "$ROOT/album/{id}"
private val PHOTO = "$ROOT/photo/{id}"

/** Photos. Content is static placeholder data, so there's no ViewModel. */
fun NavGraphBuilder.photosGraph(nav: NavHostController) {
    val data = PhotosSampleData
    val openPhoto = { photo: Photo -> nav.navigate("$ROOT/photo/${photo.id}") }
    val idArg = listOf(navArgument("id") { type = NavType.IntType })
    navigation(startDestination = MAIN, route = ROOT) {
        composable(MAIN) {
            PhotosMainScreen(data.photos, onOpenPhoto = openPhoto, onAlbums = { nav.navigate(ALBUMS) })
        }
        composable(ALBUMS) {
            AlbumsScreen(
                smartAlbums = data.smartAlbums,
                userAlbums = data.userAlbums,
                onOpenAlbum = { nav.navigate("$ROOT/album/${it.id}") },
                onBack = { nav.popBackStack() },
                onAdd = {},
            )
        }
        composable(ALBUM, arguments = idArg) { entry ->
            val album = data.album(entry.arguments?.getInt("id") ?: -1)
            if (album != null) {
                AlbumScreen(album, data.photosIn(album), onOpenPhoto = openPhoto, onBack = { nav.popBackStack() })
            }
        }
        composable(PHOTO, arguments = idArg) { entry ->
            PhotoViewScreen(Photo(entry.arguments?.getInt("id") ?: 0), onBack = { nav.popBackStack() })
        }
    }
}
