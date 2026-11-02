package hrln.interactive.sidekit.apps.webbrowser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
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
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.components.rememberSwipeRevealGroup
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class WebPage(val address: String, val site: String, val heading: String, val body: String, val link: String)

object WebBrowserSampleData {
    // Placeholder content from "Web browser / Home" (96:830).
    val page = WebPage(
        address = "example.com",
        site = "EXAMPLE.COM",
        heading = "A quieter web.",
        body = "Read, explore, and find what you need. One page at a time.",
        link = "Start reading →",
    )

    /** What a new tab shows: the mock's tile, as the browser's own start page. */
    val start = WebPage(
        address = "Search or address",
        site = "SIDEKIT",
        heading = "A quieter web.",
        body = "Type an address or a search with the keypad, then Done.",
        link = "Search →",
    )
}

object WebBrowserTags {
    const val ADDRESS = "browser_address"
    const val PAGE = "browser_page"
}

fun NavGraphBuilder.webBrowserGraph(nav: NavHostController) {
    composable(SubApp.WebBrowser.route) {
        val context = LocalContext.current.applicationContext
        val vm: BrowserViewModel = viewModel(factory = remember(context) { viewModelFactory { initializer { BrowserViewModel(Browser.repository(context)) } } })
        BrowserRoute(vm, onExit = { nav.popBackStack() })
    }
}

/** "Web browser / Home" (96:830), as drawn in the mock: the start page of a new tab. */
@Composable
fun WebBrowserScreen(page: WebPage, onTabs: () -> Unit = {}, onSearch: () -> Unit = {}) {
    SidekitScaffold(
        title = "Web browser",
        actions = dualArrowActions("Tabs", "Search", onTabs, onSearch),
    ) { padding ->
        EverydayContent(padding) {
            SidekitItem("Address", Modifier.height(CompactRowHeight), secondary = page.address)
            StartTile(page, onSearch)
        }
    }
}

/** "Example website" (96:886): 308 high, blocks 16 apart from y 18. */
@Composable
private fun StartTile(page: WebPage, onLink: () -> Unit, modifier: Modifier = Modifier) {
    SidekitTile(modifier.fillMaxWidth().height(308.dp), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.padding(start = SidekitTheme.spacing.itemPadding, end = SidekitTheme.spacing.itemPadding, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SidekitText(page.site, style = SidekitTheme.typography.small, color = SidekitTheme.colors.surfaceA6)
            SidekitText(page.heading, style = SidekitTheme.typography.headline)
            // Two lines on a 38 pitch (76 high) in the mock; label size matches its wrap.
            SidekitText(page.body, style = SidekitTheme.typography.label.copy(lineHeight = 38.sp), maxLines = 2)
            SidekitText(page.link, Modifier.clickable(onClick = onLink), style = SidekitTheme.typography.label)
        }
    }
}

/** The bar for a moment after something happened (download started, link has no app). */
private fun withNotice(notice: String?, onDismiss: () -> Unit, actions: List<SideAction>): List<SideAction> =
    if (notice == null) actions else listOf(SideAction("notice", notice, tone = ActionTone.Muted, dense = true, motion = ActionMotion.Up, onClick = onDismiss))

private fun withUndo(undo: BrowserUndo?, onUndo: () -> Unit, main: SideAction): List<SideAction> =
    if (undo == null) listOf(main) else listOf(
        SideAction("undo", "Undo", SidekitIcon.Undo, tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Undo", onClick = onUndo),
        main,
    )

/**
 * The browser. One route; the page, tab list, bookmarks and history are modes of it so the live
 * WebView survives a look at the tab list. Leaving the app destroys the WebView; its tab keeps
 * the back/forward list in memory and the address on disk.
 */
@Composable
fun BrowserRoute(vm: BrowserViewModel, onExit: () -> Unit) {
    val data = vm.data.collectAsStateWithLifecycle().value
    val mode by vm.mode.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    if (data == null) {
        SidekitScaffold(title = "Web browser") {}
        return
    }
    val tab = data.currentTab ?: return
    val context = LocalContext.current

    // One WebView, only for a tab that has a page; rebuilt for another tab or after a renderer crash.
    val engine = remember(tab.id, tab.isBlank, generation) {
        if (tab.isBlank) null else BrowserEngine.create(context, tab, data.textZoom, vm)
    }
    DisposableEffect(engine) { onDispose { engine?.saveAndDestroy() } }
    LaunchedEffect(engine) {
        if (engine != null) vm.loads.collect { url -> if (url != engine.page.url) engine.load(url) }
    }
    LaunchedEffect(engine, data.textZoom) { engine?.setTextZoom(data.textZoom) }
    // Timers and scripts only run while the page is on screen and the app is in front.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { engine?.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (mode == BrowserMode.Page) engine?.resume() }
    LaunchedEffect(engine, mode) { if (mode == BrowserMode.Page) engine?.resume() else engine?.pause() }

    val showTabs = { engine?.captureThumbnail(); vm.show(BrowserMode.Tabs) }
    when (mode) {
        BrowserMode.Page -> PageScreen(
            data = data,
            tab = tab,
            engine = engine,
            notice = notice,
            onGo = vm::go,
            onTabs = showTabs,
            onExit = onExit,
            onBookmark = { engine?.page?.let { vm.toggleBookmark(it.url, it.title) } },
            onTextZoom = vm::cycleTextZoom,
            onOpen = vm::open,
            onDismissNotice = vm::dismissNotice,
        )
        BrowserMode.Tabs -> TabsScreen(
            data = data,
            undo = undo,
            notice = notice,
            onBack = { vm.show(BrowserMode.Page) },
            onSelect = vm::selectTab,
            onClose = vm::closeTab,
            onNew = vm::newTab,
            onUndo = vm::undo,
            onBookmarks = { vm.show(BrowserMode.Bookmarks) },
            onHistory = { vm.show(BrowserMode.History) },
            onDownloads = {
                try {
                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    vm.say("No downloads app on this phone")
                }
            },
            onDismissNotice = vm::dismissNotice,
        )
        BrowserMode.Bookmarks -> BookmarksScreen(
            bookmarks = data.bookmarks,
            undo = undo,
            onBack = { vm.show(BrowserMode.Tabs) },
            onOpen = { vm.open(it.url) },
            onRemove = { vm.removeBookmark(it.url) },
            onUndo = vm::undo,
        )
        BrowserMode.History -> HistoryScreen(
            history = data.history,
            notice = notice,
            onBack = { vm.show(BrowserMode.Tabs) },
            onOpen = { vm.open(it.url) },
            onRemove = vm::removeVisit,
            onClear = vm::clearHistory,
            onDismissNotice = vm::dismissNotice,
        )
    }
}

/**
 * The page: address row above the live page. The bar is "← Tabs | Search →"; once the page has
 * history a square back button splits out on the left (the keypad's Back key does the same, and
 * only leaves the browser once there's nothing to go back to). Options hold forward, reload,
 * bookmark and text size.
 */
@Composable
private fun PageScreen(
    data: BrowserData,
    tab: BrowserTab,
    engine: BrowserEngine?,
    notice: String?,
    onGo: (String) -> Unit,
    onTabs: () -> Unit,
    onExit: () -> Unit,
    onBookmark: () -> Unit,
    onTextZoom: () -> Unit,
    onOpen: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val page = engine?.page
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val canGoBack = page?.canGoBack == true

    val submit = {
        val text = typed
        typed = ""
        focusManager.clearFocus()
        if (text.isNotBlank()) onGo(text)
    }
    BackHandler {
        when {
            editing -> { typed = ""; focusManager.clearFocus() }
            canGoBack -> engine?.goBack()
            else -> onExit()
        }
    }

    val actions = when {
        // With a touch keyboard (T9 has its own bar while typing).
        editing -> listOf(
            SideAction("back", "Cancel", tone = ActionTone.Muted, motion = ActionMotion.Up) { typed = ""; focusManager.clearFocus() },
            SideAction("forward", "Go", SidekitIcon.ArrowRightAlt, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Go", onClick = submit),
        )
        else -> listOfNotNull(
            if (canGoBack) SideAction.square("pageback", SidekitIcon.ArrowLeftAlt, "Back a page", { engine?.goBack() }, motion = ActionMotion.Back) else null,
            if (canGoBack) SideAction("back", "Tabs", contentDescription = "Tabs", onClick = onTabs) else SideAction.back("back", "Tabs", onTabs),
            SideAction.forward("forward", "Search", { focus.requestFocus() }),
        )
    }
    val url = page?.url ?: tab.url
    val options = buildList {
        if (page?.canGoForward == true) add(SideAction.square("fwd", SidekitIcon.ArrowRightAlt, "Forward a page", { engine.goForward() }))
        if (engine != null) {
            if (page?.loading == true) add(SideAction("reload", "Stop", SidekitIcon.Close) { engine.stop() })
            else add(SideAction("reload", "Reload", contentDescription = "Reload page") { engine.reload() })
            add(SideAction("bookmark", if (data.isBookmarked(url)) "Saved" else "Save", SidekitIcon.Bookmark, contentDescription = if (data.isBookmarked(url)) "Remove bookmark" else "Bookmark page", onClick = onBookmark))
        }
        add(SideAction("zoom", "Aa ${data.textZoom}%", contentDescription = "Text size ${data.textZoom} percent", onClick = onTextZoom))
    }

    SidekitScaffold(title = "Web browser", actions = withNotice(notice, onDismissNotice, actions), options = options) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AddressRow(
                typed = typed,
                onTyped = { typed = it },
                host = BrowserUrls.host(url).ifEmpty { null },
                page = page,
                focus = focus,
                editing = editing,
                onEditing = { editing = it },
                onDone = submit,
            )
            Spacer(Modifier.height(SidekitTheme.spacing.itemOuterGap))
            if (engine != null) {
                AndroidView(
                    factory = { engine.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(SidekitTheme.shapes.item)
                        .background(SidekitTheme.colors.light)
                        .testTag(WebBrowserTags.PAGE),
                )
            } else {
                StartPage(data.bookmarks, onSearch = { focus.requestFocus() }, onOpen = onOpen, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * "Address  example.com": the host sits in the field as its placeholder, so focusing the field
 * (tap, "Search", or just typing on the keypad) starts a fresh address or search. A loading
 * page fills the row from the left, drawn in the draw phase from the progress.
 */
@Composable
private fun AddressRow(
    typed: String,
    onTyped: (String) -> Unit,
    host: String?,
    page: PageState?,
    focus: FocusRequester,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val progressColor = SidekitTheme.colors.primaryA1
    SidekitTile(
        Modifier
            .fillMaxWidth()
            .height(CompactRowHeight)
            .drawBehind {
                val p = page ?: return@drawBehind
                if (!p.loading) return@drawBehind
                val f = p.progress.floatValue.coerceIn(0.05f, 1f)
                drawRect(progressColor.copy(alpha = 0.45f), size = Size(size.width * f, size.height))
            },
        onClick = { focus.requestFocus() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
        ) {
            if (!editing) SidekitText("Address")
            SidekitTextField(
                value = typed,
                onValueChange = onTyped,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onEditing(it.isFocused) }
                    .testTag(WebBrowserTags.ADDRESS),
                placeholder = if (editing) "Search or address" else host ?: "Search or address",
                focusRequester = focus,
                focusOnKeypad = true,
                singleLine = true,
                onDone = onDone,
            )
        }
    }
}

/** A blank tab: the mock's tile, then bookmarks to jump to. */
@Composable
private fun StartPage(bookmarks: List<Bookmark>, onSearch: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    Box(modifier.fillMaxWidth()) {
        SidekitList(androidx.compose.foundation.layout.PaddingValues(), state = listState) {
            item(key = "start") { StartTile(WebBrowserSampleData.start, onSearch, Modifier.listFade(listState, 0)) }
            itemsIndexed(bookmarks, key = { _, b -> b.url }) { i, b ->
                SidekitItem(b.title, Modifier.listFade(listState, i + 1), secondary = BrowserUrls.host(b.url), icon = SidekitIcon.Bookmark, onClick = { onOpen(b.url) })
            }
        }
    }
}

/** Open tabs (swipe one left to close it, with undo), then bookmarks, history and downloads. */
@Composable
private fun TabsScreen(
    data: BrowserData,
    undo: BrowserUndo?,
    notice: String?,
    onBack: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onUndo: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val main = if (undo != null) {
        withUndo(undo, onUndo, SideAction("forward", "New tab", SidekitIcon.Add, iconAtEnd = true, motion = ActionMotion.Forward, onClick = onNew))
    } else {
        listOf(SideAction.back("back", "Page", onBack), SideAction("forward", "New tab", SidekitIcon.Add, iconAtEnd = true, motion = ActionMotion.Forward, onClick = onNew))
    }
    SidekitScaffold(title = if (data.tabs.size == 1) "1 tab" else "${data.tabs.size} tabs", actions = withNotice(notice, onDismissNotice, main)) { padding ->
        val listState = rememberLazyListState()
        val swipe = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            itemsIndexed(data.tabs, key = { _, t -> t.id }, contentType = { _, _ -> "tab" }) { i, t ->
                SidekitSwipeRow(
                    key = t.id,
                    group = swipe,
                    onDelete = { onClose(t.id) },
                    modifier = Modifier.animateItem().listFade(listState, i),
                    deleteLabel = "Close ${t.label}",
                ) {
                    SidekitItem(
                        t.label,
                        secondary = BrowserUrls.host(t.url).takeIf { it != t.label },
                        colour = if (t.id == data.currentTabId) ItemColour.Secondary else ItemColour.Primary,
                        onClick = { onSelect(t.id) },
                        leading = { TabThumbnail(t.id) },
                    )
                }
            }
            val n = data.tabs.size
            item(key = "bookmarks") { SidekitItem("Bookmarks", Modifier.listFade(listState, n), secondary = data.bookmarks.size.toString(), icon = SidekitIcon.Bookmark, onClick = onBookmarks) }
            item(key = "history") { SidekitItem("History", Modifier.listFade(listState, n + 1), icon = SidekitIcon.Undo, showLinkArrow = true, onClick = onHistory) }
            item(key = "downloads") { SidekitItem("Downloads", Modifier.listFade(listState, n + 2), icon = SidekitIcon.FolderCopy, showLinkArrow = true, onClick = onDownloads) }
        }
    }
}

@Composable
private fun TabThumbnail(tabId: Long) {
    val bitmap = remember(tabId) { BrowserTabMemory.thumbnail(tabId)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, Modifier.size(56.dp).clip(SidekitTheme.shapes.control), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
    } else {
        Box(Modifier.size(56.dp).clip(SidekitTheme.shapes.control).background(SidekitTheme.colors.surfaceA4))
    }
}

@Composable
private fun BookmarksScreen(
    bookmarks: List<Bookmark>,
    undo: BrowserUndo?,
    onBack: () -> Unit,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onUndo: () -> Unit,
) {
    BackHandler(onBack = onBack)
    SidekitScaffold(title = "Bookmarks", actions = withUndo(undo, onUndo, SideAction.back("back", "Tabs", onBack))) { padding ->
        val listState = rememberLazyListState()
        val swipe = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            if (bookmarks.isEmpty()) item(key = "empty") { Hint("No bookmarks yet. On a page, the Options key has Save.", Modifier.listFade(listState, 0)) }
            itemsIndexed(bookmarks, key = { _, b -> b.url }) { i, b ->
                SidekitSwipeRow(key = b.url, group = swipe, onDelete = { onRemove(b) }, modifier = Modifier.animateItem().listFade(listState, i), deleteLabel = "Remove ${b.title}") {
                    SidekitItem(b.title, secondary = BrowserUrls.host(b.url).takeIf { it != b.title }, onClick = { onOpen(b) })
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    history: List<Visit>,
    notice: String?,
    onBack: () -> Unit,
    onOpen: (Visit) -> Unit,
    onRemove: (Visit) -> Unit,
    onClear: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (confirming) confirming = false else onBack() }
    val actions = if (confirming) {
        listOf(
            SideAction("back", "Cancel", tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Keep history") { confirming = false },
            SideAction("forward", "Clear", SidekitIcon.DeleteForever, tone = ActionTone.Destructive, weight = 1.2f, motion = ActionMotion.Up, contentDescription = "Confirm clear history") { confirming = false; onClear() },
        )
    } else {
        listOf(SideAction.back("back", "Tabs", onBack))
    }
    SidekitScaffold(
        title = "History",
        actions = withNotice(notice, onDismissNotice, actions),
        options = if (history.isNotEmpty()) listOf(SideAction("clear", "Clear history", SidekitIcon.DeleteForever, tone = ActionTone.Destructive) { confirming = true }) else emptyList(),
    ) { padding ->
        val listState = rememberLazyListState()
        val swipe = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            if (history.isEmpty()) item(key = "empty") { Hint("Pages you visit appear here.", Modifier.listFade(listState, 0)) }
            itemsIndexed(history, key = { _, v -> "${v.at}|${v.url}" }) { i, v ->
                SidekitSwipeRow(key = "${v.at}|${v.url}", group = swipe, onDelete = { onRemove(v) }, modifier = Modifier.animateItem().listFade(listState, i), deleteLabel = "Remove ${v.title}") {
                    SidekitItem(v.title.ifBlank { BrowserUrls.host(v.url) }, secondary = BrowserUrls.host(v.url).takeIf { v.title.isNotBlank() }, onClick = { onOpen(v) })
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    SidekitCard(modifier.fillMaxWidth(), color = SidekitTheme.colors.surfaceA2) {
        SidekitText(text, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
    }
}

@SidephonePreview
@Composable
private fun WebBrowserPreview() = SidekitPreviewFrame { WebBrowserScreen(WebBrowserSampleData.page) }
