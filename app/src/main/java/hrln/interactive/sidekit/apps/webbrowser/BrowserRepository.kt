package hrln.interactive.sidekit.apps.webbrowser

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** A tab: only its address and title are kept; just one tab at a time has a live WebView. */
@Immutable
data class BrowserTab(val id: Long, val url: String, val title: String) {
    val isBlank: Boolean get() = url.isEmpty()
    val label: String get() = title.ifBlank { BrowserUrls.host(url).ifEmpty { "New tab" } }
}

@Immutable
data class Bookmark(val url: String, val title: String)

@Immutable
data class Visit(val url: String, val title: String, val at: Long)

@Immutable
data class BrowserData(
    val tabs: List<BrowserTab> = emptyList(),
    val currentTabId: Long = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    /** Newest first, capped at [BrowserRepository.HISTORY_MAX]. */
    val history: List<Visit> = emptyList(),
    /** WebView text zoom, percent. */
    val textZoom: Int = BrowserRepository.DEFAULT_TEXT_ZOOM,
) {
    val currentTab: BrowserTab? get() = tabs.firstOrNull { it.id == currentTabId } ?: tabs.firstOrNull()
    fun isBookmarked(url: String) = url.isNotEmpty() && bookmarks.any { it.url == url }
}

/** Turning what's typed into the address bar into a URL. */
object BrowserUrls {
    /** DuckDuckGo's HTML-only "lite" results: a few KB, no scripts, readable at 480 px. */
    private const val SEARCH = "https://lite.duckduckgo.com/lite/?q="
    private val HOST = Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+(:\\d{1,5})?(/.*)?$")

    /**
     * A web address for [input]: http(s) URLs as typed, bare domains ("example.com/news") over
     * https, anything else a search. Other schemes (file:, content:, javascript:) are searched for,
     * never loaded.
     */
    fun resolve(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val lower = t.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t
        if (' ' !in t && HOST.matches(t)) return "https://$t"
        if (lower == "localhost" || lower.startsWith("localhost:")) return "http://$t"
        return SEARCH + URLEncoder.encode(t, "UTF-8")
    }

    /** "news.example.com" for a URL, without "www."; empty if there's none. */
    fun host(url: String): String = try {
        URI(url).host?.removePrefix("www.").orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun isWeb(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}

interface BrowserStore {
    fun read(): BrowserData
    fun write(data: BrowserData)
}

/** Tabs, bookmarks, history and settings in one small binary file, replaced atomically. */
class FileBrowserStore(file: File) : BrowserStore {
    private val file = AtomicFile(file)

    override fun read(): BrowserData {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return BrowserData()
        }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return BrowserData()
            val current = input.readLong()
            val zoom = input.readInt()
            val tabs = List(input.readInt()) { BrowserTab(input.readLong(), input.readUTF(), input.readUTF()) }
            val bookmarks = List(input.readInt()) { Bookmark(input.readUTF(), input.readUTF()) }
            val history = List(input.readInt()) { Visit(input.readUTF(), input.readUTF(), input.readLong()) }
            BrowserData(tabs, current, bookmarks, history, zoom)
        }
    }

    override fun write(data: BrowserData) {
        val out = file.startWrite()
        try {
            val d = DataOutputStream(BufferedOutputStream(out))
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            d.writeLong(data.currentTabId)
            d.writeInt(data.textZoom)
            d.writeInt(data.tabs.size)
            data.tabs.forEach { d.writeLong(it.id); d.writeUTF(it.url.take(URL_MAX)); d.writeUTF(it.title.take(TITLE_MAX)) }
            d.writeInt(data.bookmarks.size)
            data.bookmarks.forEach { d.writeUTF(it.url.take(URL_MAX)); d.writeUTF(it.title.take(TITLE_MAX)) }
            d.writeInt(data.history.size)
            data.history.forEach { d.writeUTF(it.url.take(URL_MAX)); d.writeUTF(it.title.take(TITLE_MAX)); d.writeLong(it.at) }
            d.flush()
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B5742 // "SKWB"
        const val VERSION = 1

        // writeUTF holds at most 64 KB; real URLs are far shorter, data: URLs aren't stored.
        const val URL_MAX = 8_000
        const val TITLE_MAX = 300
    }
}

/**
 * The browser's saved state, shared by every visit. Edits are in memory and synchronous; the file
 * is written behind them on [io], conflated (a page load updates the tab and history at once).
 */
class BrowserRepository(
    private val store: BrowserStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _data = MutableStateFlow<BrowserData?>(null)

    /** Null until the file has been read. Always has at least one tab once loaded. */
    val data: StateFlow<BrowserData?> = _data.asStateFlow()

    private var lastId = 0L

    init {
        scope.launch(io) {
            val loaded = try {
                store.read()
            } catch (_: IOException) {
                BrowserData()
            }
            var written: BrowserData? = null
            _data.update { current ->
                (current ?: withTab(loaded)).also { if (current == null && loaded.tabs.isNotEmpty()) written = it }
            }
            synchronized(this@BrowserRepository) { lastId = maxOf(lastId, loaded.tabs.maxOfOrNull { it.id } ?: 0L) }
            _data.filterNotNull().conflate().collect { d ->
                if (d !== written) {
                    try {
                        store.write(d)
                        written = d
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun withTab(d: BrowserData): BrowserData {
        if (d.tabs.isNotEmpty()) return d
        val id = nextId(d)
        return d.copy(tabs = listOf(BrowserTab(id, "", "")), currentTabId = id)
    }

    fun newTab(url: String = ""): Long {
        var id = 0L
        mutate { d ->
            id = nextId(d)
            d.copy(tabs = d.tabs + BrowserTab(id, url, ""), currentTabId = id)
        }
        return id
    }

    fun selectTab(id: Long) = mutate { d -> if (d.tabs.any { it.id == id }) d.copy(currentTabId = id) else d }

    /** Closes a tab, returning it and its position for undo. The last tab is replaced by a blank one. */
    fun closeTab(id: Long): Pair<BrowserTab, Int>? {
        val d = _data.value ?: return null
        val index = d.tabs.indexOfFirst { it.id == id }
        if (index < 0) return null
        val tab = d.tabs[index]
        mutate { cur ->
            val rest = cur.tabs.filter { it.id != id }
            val tabs = rest.ifEmpty { listOf(BrowserTab(nextId(cur), "", "")) }
            val current = if (cur.currentTabId == id) tabs[(index - 1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)].id else cur.currentTabId
            cur.copy(tabs = tabs, currentTabId = current)
        }
        return tab to index
    }

    fun restoreTab(tab: BrowserTab, index: Int) = mutate { d ->
        if (d.tabs.any { it.id == tab.id }) return@mutate d
        // Drop the blank tab that stood in for the last one closed.
        val tabs = d.tabs.filterNot { it.isBlank && d.tabs.size == 1 }.toMutableList()
        tabs.add(index.coerceIn(0, tabs.size), tab)
        d.copy(tabs = tabs, currentTabId = tab.id)
    }

    fun updateTab(id: Long, url: String, title: String) = mutate { d ->
        d.copy(tabs = d.tabs.map { if (it.id == id && (it.url != url || it.title != title)) it.copy(url = url, title = title) else it })
    }

    /** Records a finished page load; the same address twice in a row just refreshes the entry. */
    fun visited(url: String, title: String) {
        if (!BrowserUrls.isWeb(url)) return
        mutate { d ->
            val previous = d.history.firstOrNull()
            val visit = Visit(url, title, clock())
            val history = if (previous?.url == url) listOf(visit) + d.history.drop(1) else listOf(visit) + d.history
            d.copy(history = history.take(HISTORY_MAX))
        }
    }

    /** Adds or removes a bookmark; returns true if [url] is bookmarked afterwards. */
    fun toggleBookmark(url: String, title: String): Boolean {
        if (url.isEmpty()) return false
        var added = false
        mutate { d ->
            if (d.isBookmarked(url)) {
                d.copy(bookmarks = d.bookmarks.filter { it.url != url })
            } else {
                added = true
                d.copy(bookmarks = listOf(Bookmark(url, title.ifBlank { BrowserUrls.host(url) })) + d.bookmarks)
            }
        }
        return added
    }

    fun removeBookmark(url: String): Bookmark? {
        val b = _data.value?.bookmarks?.firstOrNull { it.url == url } ?: return null
        mutate { d -> d.copy(bookmarks = d.bookmarks.filter { it.url != url }) }
        return b
    }

    fun restoreBookmark(b: Bookmark) = mutate { d -> if (d.isBookmarked(b.url)) d else d.copy(bookmarks = listOf(b) + d.bookmarks) }

    fun removeVisit(v: Visit) = mutate { d -> d.copy(history = d.history.filter { it != v }) }

    fun clearHistory() = mutate { d -> d.copy(history = emptyList()) }

    fun setTextZoom(percent: Int) = mutate { d -> d.copy(textZoom = percent.coerceIn(50, 300)) }

    private inline fun mutate(crossinline change: (BrowserData) -> BrowserData) =
        _data.update { change(withTab(it ?: BrowserData())) }

    @Synchronized
    private fun nextId(d: BrowserData): Long {
        lastId = maxOf(lastId, d.tabs.maxOfOrNull { it.id } ?: 0L) + 1
        return lastId
    }

    companion object {
        const val HISTORY_MAX = 300
        const val DEFAULT_TEXT_ZOOM = 125
        val TEXT_ZOOMS = listOf(100, 125, 150, 175)
    }
}

/**
 * What a tab keeps while it has no live WebView: its back/forward list (WebView.saveState) and a
 * small thumbnail for the tab list. Memory only, and bounded: a tab beyond [MAX] just reloads.
 */
object BrowserTabMemory {
    private const val MAX = 8
    private val states = object : LinkedHashMap<Long, Bundle>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bundle>?) = size > MAX
    }
    private val thumbs = object : LinkedHashMap<Long, Bitmap>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > MAX
    }

    @Synchronized fun state(tab: Long): Bundle? = states[tab]
    @Synchronized fun putState(tab: Long, state: Bundle) { states[tab] = state }
    @Synchronized fun thumbnail(tab: Long): Bitmap? = thumbs[tab]
    @Synchronized fun putThumbnail(tab: Long, bitmap: Bitmap) { thumbs[tab] = bitmap }

    @Synchronized fun forget(tab: Long) {
        states.remove(tab)
        thumbs.remove(tab)
    }
}

object Browser {
    @Volatile private var repository: BrowserRepository? = null

    fun repository(context: Context): BrowserRepository = repository ?: synchronized(this) {
        repository ?: BrowserRepository(
            FileBrowserStore(File(context.applicationContext.filesDir, "browser.bin")),
            CoroutineScope(SupervisorJob()),
        ).also { repository = it }
    }
}
