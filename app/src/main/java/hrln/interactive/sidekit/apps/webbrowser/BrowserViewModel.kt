package hrln.interactive.sidekit.apps.webbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which list the browser shows instead of the page. The page's WebView stays alive behind them. */
enum class BrowserMode { Page, Tabs, Bookmarks, History }

/** Something just removed, offered back by an Undo split in the bar. */
sealed interface BrowserUndo {
    data class Tab(val tab: BrowserTab, val index: Int) : BrowserUndo
    data class Saved(val bookmark: Bookmark) : BrowserUndo
}

class BrowserViewModel(
    private val repository: BrowserRepository,
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
) : ViewModel(), BrowserHost {
    val data: StateFlow<BrowserData?> = repository.data

    private val _mode = MutableStateFlow(BrowserMode.Page)
    val mode: StateFlow<BrowserMode> = _mode.asStateFlow()

    private val _undo = MutableStateFlow<BrowserUndo?>(null)
    val undo: StateFlow<BrowserUndo?> = _undo.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Addresses for the live page to load (a page that's already open is told directly). */
    private val _loads = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val loads: SharedFlow<String> = _loads.asSharedFlow()

    /** Bumped when the renderer dies, so the screen builds a new WebView. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private var undoJob: Job? = null
    private var noticeJob: Job? = null

    fun show(mode: BrowserMode) {
        _mode.value = mode
    }

    /** Loads what was typed (an address or a search) in the current tab. */
    fun go(input: String) {
        val url = BrowserUrls.resolve(input)
        if (url.isEmpty()) return
        open(url)
    }

    /** Opens [url] in the current tab and returns to the page. */
    fun open(url: String) {
        val tab = repository.data.value?.currentTab ?: return
        repository.updateTab(tab.id, url, "")
        _loads.tryEmit(url)
        _mode.value = BrowserMode.Page
    }

    fun newTab() {
        repository.newTab()
        _mode.value = BrowserMode.Page
    }

    fun selectTab(id: Long) {
        repository.selectTab(id)
        _mode.value = BrowserMode.Page
    }

    fun closeTab(id: Long) {
        val (tab, index) = repository.closeTab(id) ?: return
        offerUndo(BrowserUndo.Tab(tab, index))
    }

    fun removeBookmark(url: String) {
        val b = repository.removeBookmark(url) ?: return
        offerUndo(BrowserUndo.Saved(b))
    }

    fun undo() {
        val u = _undo.value ?: return
        undoJob?.cancel()
        when (u) {
            is BrowserUndo.Tab -> repository.restoreTab(u.tab, u.index)
            is BrowserUndo.Saved -> repository.restoreBookmark(u.bookmark)
        }
        _undo.value = null
    }

    fun toggleBookmark(url: String, title: String) {
        say(if (repository.toggleBookmark(url, title)) "Bookmarked" else "Bookmark removed")
    }

    fun removeVisit(v: Visit) = repository.removeVisit(v)

    fun clearHistory() {
        repository.clearHistory()
        say("History cleared")
    }

    /** Steps through [BrowserRepository.TEXT_ZOOMS]. */
    fun cycleTextZoom() {
        val zooms = BrowserRepository.TEXT_ZOOMS
        val current = repository.data.value?.textZoom ?: BrowserRepository.DEFAULT_TEXT_ZOOM
        val next = zooms[(zooms.indexOf(current) + 1).mod(zooms.size)]
        repository.setTextZoom(next)
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }

    fun say(message: String) {
        _notice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    // BrowserHost: called by the live page on the main thread.
    override fun onPage(tabId: Long, url: String, title: String) {
        if (url.isNotEmpty() && BrowserUrls.isWeb(url)) repository.updateTab(tabId, url, title)
    }

    override fun onVisited(url: String, title: String) = repository.visited(url, title)

    override fun onNotice(message: String) = say(message)

    override fun onRendererGone() {
        _generation.value++
        say("The page stopped; reloading")
    }

    private fun offerUndo(u: BrowserUndo) {
        _undo.value = u
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(undoWindowMs)
            _undo.value = null
        }
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
        const val NOTICE_MS = 2_500L
    }
}
