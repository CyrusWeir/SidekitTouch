package hrln.interactive.sidekit.apps.files

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class FileLocation(val name: String, val detail: String)

object FilesSampleData {
    // Placeholder content from "Files / Home" (96:696). The last row's layer is named
    // "On Sidephone · 2.4 GB free" but the mock shows "2.4 GB" (the rest doesn't fit).
    val locations = listOf(
        FileLocation("Recents", "12 files"),
        FileLocation("Documents", "8 files"),
        FileLocation("Downloads", "24 files"),
        FileLocation("Photos", "56 files"),
        FileLocation("On Sidephone", "2.4 GB"),
    )
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.filesGraph(nav: NavHostController) {
    composable(SubApp.Files.route) { FilesScreen(FilesSampleData.locations) }
}

/** "Files / Home" (96:696): compact 74-high rows. No folder screen is mocked. */
@Composable
fun FilesScreen(locations: List<FileLocation>, onBrowse: () -> Unit = {}, onSearch: () -> Unit = {}) {
    SidekitScaffold(
        title = "Files",
        actions = dualArrowActions("Browse", "Search", onBrowse, onSearch),
    ) { padding ->
        EverydayContent(padding) {
            locations.forEach { SidekitItem(it.name, Modifier.height(CompactRowHeight), secondary = it.detail) }
        }
    }
}

@SidephonePreview
@Composable
private fun FilesPreview() = SidekitPreviewFrame { FilesScreen(FilesSampleData.locations) }
