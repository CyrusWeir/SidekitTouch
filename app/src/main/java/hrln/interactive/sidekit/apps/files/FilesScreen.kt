package hrln.interactive.sidekit.apps.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitSwipeRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.components.rememberSwipeRevealGroup
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview
import kotlinx.coroutines.flow.filter

/**
 * A row of the Files home screen. [place] is the encoded [FilePlace] it opens (null for a row
 * that does something else, like "On Sidephone"); [removable] rows are picked folders.
 */
data class FileLocation(val name: String, val detail: String, val place: String? = null, val removable: Boolean = false)

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

object FilesTags {
    const val SEARCH = "files_search"
    const val LIST = "files_list"
}

/** The bar for a moment after something happened, else [actions]. Tapping the message dismisses it. */
private fun withNotice(notice: String?, onDismiss: () -> Unit, actions: List<SideAction>): List<SideAction> =
    if (notice == null) actions else listOf(SideAction("notice", notice, tone = ActionTone.Muted, dense = true, motion = ActionMotion.Up, onClick = onDismiss))

/** "Cancel | Delete" confirmation, in the bar instead of a dialog. */
private fun confirmActions(verb: String, name: String, onCancel: () -> Unit, onConfirm: () -> Unit) = listOf(
    SideAction("back", "Cancel", tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Keep $name", onClick = onCancel),
    SideAction("forward", verb, SidekitIcon.DeleteForever, tone = ActionTone.Destructive, weight = 1.2f, motion = ActionMotion.Up, contentDescription = "Confirm ${verb.lowercase()} $name", onClick = onConfirm),
)

/**
 * "Files / Home" (96:696): compact 74-high rows for the phone's media collections, the folders
 * the user has added, and free space. "← Browse" picks a folder on the phone to add (the system
 * folder picker); "Search →" searches by name. Swiping a folder left offers to remove it.
 *
 * Until media access is granted a row explains why it's needed and the bar offers "Allow".
 */
@Composable
fun FilesScreen(
    locations: List<FileLocation>,
    onBrowse: () -> Unit = {},
    onSearch: () -> Unit = {},
    onOpen: (FileLocation) -> Unit = {},
    needsPermission: Boolean = false,
    onAllow: () -> Unit = {},
    onRemove: ((FileLocation) -> Unit)? = null,
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = locations.firstOrNull { it.place != null && it.place == removing }
    BackHandler(enabled = pending != null) { removing = null }
    val actions = when {
        pending != null -> confirmActions("Remove", pending.name, { removing = null }) { onRemove?.invoke(pending); removing = null }
        needsPermission -> listOf(
            SideAction.back("back", "Browse", onBrowse),
            SideAction("forward", "Allow", SidekitIcon.Check, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Allow access to media", onClick = onAllow),
        )
        else -> listOf(SideAction.back("back", "Browse", onBrowse), SideAction.forward("forward", "Search", onSearch))
    }
    SidekitScaffold(
        title = "Files",
        actions = withNotice(notice, onDismissNotice, actions),
        options = if (needsPermission) listOf(SideAction("search", "Search", SidekitIcon.Search, onClick = onSearch)) else emptyList(),
    ) { padding ->
        val listState = rememberLazyListState()
        val swipe = rememberSwipeRevealGroup()
        SidekitList(padding, Modifier.testTag(FilesTags.LIST), state = listState) {
            if (needsPermission) {
                item(key = "permission") {
                    Explain(
                        "Files lists your photos, videos, music and downloads. Android asks once before an app can see them. Folders you add with Browse work without it.",
                        Modifier.listFade(listState, 0),
                    )
                }
            }
            val offset = if (needsPermission) 1 else 0
            itemsIndexed(locations, key = { _, l -> l.place ?: l.name }) { i, location ->
                val row = @Composable {
                    SidekitItem(
                        location.name,
                        Modifier.height(CompactRowHeight),
                        secondary = location.detail,
                        colour = if (location == pending) ItemColour.Destructive else ItemColour.Primary,
                        onClick = { onOpen(location) },
                    )
                }
                if (location.removable && onRemove != null) {
                    SidekitSwipeRow(
                        key = location.place ?: location.name,
                        group = swipe,
                        onDelete = { removing = location.place },
                        modifier = Modifier.animateItem().listFade(listState, i + offset),
                        deleteLabel = "Remove ${location.name}",
                    ) { row() }
                } else {
                    Column(Modifier.listFade(listState, i + offset)) { row() }
                }
            }
        }
    }
}

@Composable
private fun Explain(text: String, modifier: Modifier = Modifier) {
    SidekitCard(modifier.fillMaxWidth(), color = SidekitTheme.colors.surfaceA2) {
        SidekitText(text, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
    }
}

/** Builds the home rows from what the phone has. */
internal fun homeRows(home: FilesHome): List<FileLocation> {
    val media = listOf(MediaKind.Recent, MediaKind.Documents, MediaKind.Downloads, MediaKind.Images, MediaKind.Video, MediaKind.Audio).map { kind ->
        FileLocation(
            kind.title,
            if (home.mediaAllowed) FileFormat.count(home.counts[kind] ?: 0) else "Needs access",
            FilePlace.Media(kind).encode(),
        )
    }
    val folders = home.folders.map { FileLocation(it.name, "Folder", it.location.encode(), removable = true) }
    val space = FileLocation("On Sidephone", if (home.totalBytes > 0) "${FileFormat.size(home.freeBytes)} free" else "Add a folder")
    return media + folders + space
}

/**
 * A folder or collection. Tap a file to open it with an app that can; tap a folder to go in.
 * Swipe left to delete, confirmed in the bar ("Cancel | Delete"). Sort is in Options.
 */
@Composable
fun FilesBrowseScreen(
    title: String,
    entries: List<FileEntry>?,
    sort: FileSort,
    pendingDelete: FileEntry?,
    notice: String?,
    onOpen: (FileEntry) -> Unit,
    onAskDelete: (FileEntry) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onSort: (FileSort) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    BackHandler { if (pendingDelete != null) onCancelDelete() else onBack() }
    val actions = if (pendingDelete != null) {
        confirmActions("Delete", pendingDelete.name, onCancelDelete, onConfirmDelete)
    } else {
        listOf(SideAction.back("back", "Back", onBack), SideAction.forward("forward", "Search", onSearch))
    }
    SidekitScaffold(title = title, actions = withNotice(notice, onDismissNotice, actions), options = sortOptions(sort, onSort)) { padding ->
        EntryList(entries, pendingDelete, padding, onOpen, onAskDelete, empty = "Nothing here")
    }
}

private fun sortOptions(sort: FileSort, onSort: (FileSort) -> Unit) = FileSort.entries.map { s ->
    SideAction("sort.${s.name}", s.label, tone = if (s == sort) ActionTone.Primary else ActionTone.Muted, contentDescription = "Sort by ${s.label}") { onSort(s) }
}

@Composable
private fun EntryList(
    entries: List<FileEntry>?,
    pendingDelete: FileEntry?,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onOpen: (FileEntry) -> Unit,
    onAskDelete: (FileEntry) -> Unit,
    empty: String?,
    header: (@Composable (Modifier) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val swipe = rememberSwipeRevealGroup()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { swipe.closeAll() }
    }
    val offset = if (header != null) 1 else 0
    SidekitList(padding, Modifier.testTag(FilesTags.LIST), state = listState) {
        if (header != null) item(key = "header", contentType = "header") { header(Modifier.listFade(listState, 0)) }
        when {
            entries == null -> item(key = "loading") { Explain("Loading…", Modifier.listFade(listState, offset)) }
            entries.isEmpty() && empty != null -> item(key = "empty") { Explain(empty, Modifier.listFade(listState, offset)) }
            else -> itemsIndexed(entries, key = { _, e -> e.key }, contentType = { _, _ -> "entry" }) { i, e ->
                SidekitSwipeRow(
                    key = e.key,
                    group = swipe,
                    onDelete = { onAskDelete(e) },
                    modifier = Modifier.animateItem().listFade(listState, i + offset),
                    deleteLabel = "Delete ${e.name}",
                ) {
                    SidekitItem(
                        e.name,
                        Modifier.height(CompactRowHeight),
                        secondary = FileFormat.detail(e),
                        icon = if (e.isDirectory) SidekitIcon.FolderCopy else null,
                        colour = if (e.key == pendingDelete?.key) ItemColour.Destructive else ItemColour.Primary,
                        onClick = { onOpen(e) },
                    )
                }
            }
        }
    }
}

/** Search by name across the phone's media and the added folders. */
@Composable
fun FilesSearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<FileEntry>?,
    sort: FileSort,
    pendingDelete: FileEntry?,
    notice: String?,
    onOpen: (FileEntry) -> Unit,
    onAskDelete: (FileEntry) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onSort: (FileSort) -> Unit,
    onBack: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    BackHandler { if (pendingDelete != null) onCancelDelete() else onBack() }
    val actions = if (pendingDelete != null) {
        confirmActions("Delete", pendingDelete.name, onCancelDelete, onConfirmDelete)
    } else {
        listOf(SideAction.back("back", "Files", onBack))
    }
    SidekitScaffold(title = "Search", actions = withNotice(notice, onDismissNotice, actions), options = sortOptions(sort, onSort)) { padding ->
        EntryList(
            entries = if (query.isBlank()) emptyList() else results,
            pendingDelete = pendingDelete,
            padding = padding,
            onOpen = onOpen,
            onAskDelete = onAskDelete,
            empty = if (query.isBlank()) null else "No files match",
            header = { m ->
                SidekitCard(m.fillMaxWidth().heightIn(min = CompactRowHeight)) {
                    SidekitTextField(
                        value = query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart).testTag(FilesTags.SEARCH),
                        placeholder = "File name",
                        focusOnKeypad = true,
                        singleLine = true,
                        autoFocus = true,
                    )
                }
            },
        )
    }
}

@SidephonePreview
@Composable
private fun FilesPreview() = SidekitPreviewFrame { FilesScreen(FilesSampleData.locations) }
