package hrln.interactive.sidekit.apps.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import hrln.interactive.sidekit.apps.media.rememberPermissionState
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.flow.MutableStateFlow

private val ROOT = SubApp.Video.route
private val LIST = "$ROOT/list"
private val PLAY = "$ROOT/play/{id}"

/** Routes other apps use to open a video (Photos plays videos here). */
object VideoRoutes {
    fun player(id: Long) = "$ROOT/play/$id"
}

private val Locked = MutableStateFlow(VideoListState())

fun NavGraphBuilder.videoGraph(nav: NavHostController) {
    navigation(startDestination = LIST, route = ROOT) {
        composable(LIST) { entry ->
            val vm = entry.videoViewModel(nav)
            val permission = rememberPermissionState(MediaPermissions.video)
            val state by (if (permission.granted) vm.state else Locked).collectAsStateWithLifecycle()
            VideoListScreen(
                state = state,
                onPlay = { if (nav.currentBackStackEntry?.id == entry.id) nav.navigate(VideoRoutes.player(it.id)) { launchSingleTop = true } },
                onBack = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() },
                access = permission.access,
                onAllow = permission::request,
            )
        }
        composable(PLAY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val context = LocalContext.current.applicationContext
            val player = remember(id) { VideoPlayer(context, Video(id, "", 0).uri) }
            // Plays on first open; after the screen has been stopped (display off, another app) it
            // comes back paused where it was.
            var opened by rememberSaveable(id) { mutableStateOf(false) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, player) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            player.start(autoplay = !opened)
                            opened = true
                        }
                        Lifecycle.Event.ON_STOP -> player.stop()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    player.stop()
                }
            }
            VideoPlayerScreen(player, onBack = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() })
        }
    }
}

/** One ViewModel per visit to Video, shared by its screens. */
@Composable
private fun NavBackStackEntry.videoViewModel(nav: NavHostController): VideoViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(parent, factory = remember(context) { viewModelFactory { initializer { VideoViewModel(MediaStoreVideoSource(context.contentResolver)) } } })
}
