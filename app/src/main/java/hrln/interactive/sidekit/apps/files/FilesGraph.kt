package hrln.interactive.sidekit.apps.files

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Files.route
private val HOME = "$ROOT/home"
private const val ARG_PLACE = "place"
private val BROWSE = "$ROOT/browse/{$ARG_PLACE}"
private val SEARCH = "$ROOT/search"

private fun browseRoute(place: FilePlace) = "$ROOT/browse/" + Uri.encode(place.encode())

/** Media read permission for this API level (READ_EXTERNAL_STORAGE on the Sidephone's API 31). */
internal fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun hasMediaAccess(context: Context) =
    mediaPermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

fun NavGraphBuilder.filesGraph(nav: NavHostController) {
    navigation(startDestination = HOME, route = ROOT) {
        composable(HOME) { entry ->
            val vm = entry.filesViewModel(nav)
            val home by vm.home.collectAsStateWithLifecycle()
            val notice by vm.notice.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val activity = LocalActivity.current
            // Counts and folders are re-read whenever the screen comes back (files change elsewhere).
            LifecycleResumeEffect(vm) {
                vm.refreshHome()
                onPauseOrDispose {}
            }
            var askedOnce by rememberSaveable { mutableStateOf(false) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                askedOnce = true
                vm.refreshHome()
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                if (tree != null) vm.addFolder(tree)?.let { nav.navigateFrom(entry, browseRoute(it.location)) }
            }
            FilesScreen(
                locations = homeRows(home),
                onBrowse = { picker.launchSafely(null) { vm.say("No folder picker on this phone") } },
                onSearch = { nav.navigateFrom(entry, SEARCH) },
                onOpen = { row ->
                    val place = row.place?.let(FilePlace::decode)
                    when {
                        place == null -> picker.launchSafely(null) { vm.say("No folder picker on this phone") }
                        place is FilePlace.Media && !home.mediaAllowed -> vm.say("Allow access first")
                        else -> nav.navigateFrom(entry, browseRoute(place))
                    }
                },
                needsPermission = !home.mediaAllowed,
                onAllow = {
                    // After a "don't ask again" denial only Settings can grant it.
                    val blocked = askedOnce && activity != null && mediaPermissions().none { activity.shouldShowRequestPermissionRationale(it) }
                    if (blocked) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    } else {
                        permission.launch(mediaPermissions())
                    }
                },
                onRemove = { row ->
                    val place = row.place?.let(FilePlace::decode) as? FilePlace.Tree
                    home.folders.firstOrNull { it.tree == place?.tree }?.let(vm::removeFolder)
                },
                notice = notice,
                onDismissNotice = vm::dismissNotice,
            )
        }
        composable(BROWSE) { entry ->
            val vm = entry.filesViewModel(nav)
            val place = remember(entry) { entry.arguments?.getString(ARG_PLACE)?.let { FilePlace.decode(Uri.decode(it)) } }
            if (place == null) {
                LaunchedEffect(Unit) { nav.popFrom(entry) }
                return@composable
            }
            LaunchedEffect(place) { vm.open(place) }
            val listing by vm.listing.collectAsStateWithLifecycle()
            val sort by vm.sort.collectAsStateWithLifecycle()
            val pending by vm.pendingDelete.collectAsStateWithLifecycle()
            val notice by vm.notice.collectAsStateWithLifecycle()
            val consent = rememberDeleteConsent(vm)
            val open = rememberFileOpener(vm)
            FilesBrowseScreen(
                title = place.title,
                entries = listing?.takeIf { it.place == place }?.entries,
                sort = sort,
                pendingDelete = pending,
                notice = notice,
                onOpen = { e -> e.folder?.let { nav.navigateFrom(entry, browseRoute(it)) } ?: open(e) },
                onAskDelete = vm::askDelete,
                onCancelDelete = vm::cancelDelete,
                onConfirmDelete = { vm.confirmDelete(consent) },
                onSort = vm::setSort,
                onBack = { nav.popFrom(entry) },
                onSearch = { nav.navigateFrom(entry, SEARCH) },
                onDismissNotice = vm::dismissNotice,
            )
        }
        composable(SEARCH) { entry ->
            val vm = entry.filesViewModel(nav)
            var query by rememberSaveable { mutableStateOf("") }
            val results by vm.results.collectAsStateWithLifecycle()
            val sort by vm.sort.collectAsStateWithLifecycle()
            val pending by vm.pendingDelete.collectAsStateWithLifecycle()
            val notice by vm.notice.collectAsStateWithLifecycle()
            val consent = rememberDeleteConsent(vm)
            val open = rememberFileOpener(vm)
            FilesSearchScreen(
                query = query,
                onQuery = { query = it; vm.search(it) },
                results = results,
                sort = sort,
                pendingDelete = pending,
                notice = notice,
                onOpen = { e -> e.folder?.let { nav.navigateFrom(entry, browseRoute(it)) } ?: open(e) },
                onAskDelete = vm::askDelete,
                onCancelDelete = vm::cancelDelete,
                onConfirmDelete = { vm.confirmDelete(consent) },
                onSort = vm::setSort,
                onBack = { nav.popFrom(entry) },
                onDismissNotice = vm::dismissNotice,
            )
        }
    }
}

/** Android's own "Allow Sidekit to delete this?" for other apps' media; the answer comes back here. */
@Composable
private fun rememberDeleteConsent(vm: FilesViewModel): (android.content.IntentSender) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.consentResult(it.resultCode == Activity.RESULT_OK)
    }
    return remember(launcher) { { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) } }
}

/** Opens a file in whichever installed app handles its type, with read access to just that file. */
@Composable
private fun rememberFileOpener(vm: FilesViewModel): (FileEntry) -> Unit {
    val context = LocalContext.current
    return remember(context, vm) {
        { e ->
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(e.uri, e.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                vm.say("No app on this phone opens this file")
            } catch (_: SecurityException) {
                vm.say("Couldn't open ${e.name}")
            }
        }
    }
}

private fun <I> androidx.activity.result.ActivityResultLauncher<I>.launchSafely(input: I, onMissing: () -> Unit) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        onMissing()
    }
}

private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id == entry.id) navigate(route)
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

@Composable
private fun NavBackStackEntry.filesViewModel(nav: NavHostController): FilesViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(
        parent,
        factory = remember(context) {
            viewModelFactory { initializer { FilesViewModel(AndroidFilesSource(context), SharedFilesPrefs(context), { hasMediaAccess(context) }) } }
        },
    )
}
