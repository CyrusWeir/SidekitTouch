package hrln.interactive.sidekit.apps.files

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Remembers the sort order between visits. */
interface FilesPrefs {
    var sort: FileSort
}

class SharedFilesPrefs(context: Context) : FilesPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("sidekit_files", Context.MODE_PRIVATE)
    override var sort: FileSort
        get() = FileSort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: FileSort.Newest
        set(value) = prefs.edit().putString("sort", value.name).apply()
}

data class FilesHome(
    val mediaAllowed: Boolean = false,
    val counts: Map<MediaKind, Int> = emptyMap(),
    val folders: List<SavedFolder> = emptyList(),
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
)

/** The open folder's contents; [entries] is null while loading. */
data class FilesListing(val place: FilePlace, val entries: List<FileEntry>?)

class FilesViewModel(
    private val source: FilesSource,
    private val prefs: FilesPrefs,
    private val mediaAllowed: () -> Boolean,
) : ViewModel() {
    private val _home = MutableStateFlow(FilesHome())
    val home: StateFlow<FilesHome> = _home.asStateFlow()

    private val _sort = MutableStateFlow(prefs.sort)
    val sort: StateFlow<FileSort> = _sort.asStateFlow()

    private val _listing = MutableStateFlow<FilesListing?>(null)
    val listing: StateFlow<FilesListing?> = _listing.asStateFlow()

    private val _results = MutableStateFlow<List<FileEntry>?>(emptyList())

    /** Search results; null while searching. */
    val results: StateFlow<List<FileEntry>?> = _results.asStateFlow()

    /** The file whose delete is awaiting "Delete" in the bar. */
    private val _pendingDelete = MutableStateFlow<FileEntry?>(null)
    val pendingDelete: StateFlow<FileEntry?> = _pendingDelete.asStateFlow()

    /** A short message shown in the action bar for a moment (e.g. "No app opens this file"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var noticeJob: Job? = null
    private var lastQuery = ""

    fun refreshHome() {
        viewModelScope.launch {
            val allowed = mediaAllowed()
            val counts = if (allowed) MediaKind.entries.associateWith { source.count(it) } else emptyMap()
            val (free, total) = source.space()
            _home.value = FilesHome(allowed, counts, source.savedFolders(), free, total)
        }
    }

    fun open(place: FilePlace) {
        val current = _listing.value
        _listing.value = FilesListing(place, current?.takeIf { it.place == place }?.entries)
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val entries = _sort.value.apply(source.list(place))
            _listing.value = FilesListing(place, entries)
        }
    }

    fun setSort(sort: FileSort) {
        prefs.sort = sort
        _sort.value = sort
        _listing.update { it?.copy(entries = it.entries?.let(sort::apply)) }
        _results.update { r -> r?.let(sort::apply) }
    }

    fun search(query: String) {
        if (query == lastQuery) return
        lastQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _results.value = null
            _results.value = _sort.value.apply(source.search(query, source.savedFolders()))
        }
    }

    fun addFolder(tree: Uri): SavedFolder? = source.addFolder(tree)?.also { refreshHome() }

    fun removeFolder(folder: SavedFolder) {
        source.removeFolder(folder)
        refreshHome()
    }

    fun askDelete(entry: FileEntry) {
        if (!entry.canDelete) {
            say("This file can't be deleted here")
            return
        }
        _pendingDelete.value = entry
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    /**
     * Deletes the pending file. If Android must ask the user first (another app's media), hands
     * the system request to [onConsent]; [consentResult] finishes the job.
     */
    fun confirmDelete(onConsent: (IntentSender) -> Unit) {
        val entry = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            when (val r = source.delete(entry)) {
                DeleteResult.Deleted -> removed(entry)
                is DeleteResult.NeedsConsent -> {
                    awaitingConsent = entry
                    onConsent(r.request)
                }
                DeleteResult.Failed -> say("Couldn't delete ${entry.name}")
            }
        }
    }

    private var awaitingConsent: FileEntry? = null

    fun consentResult(granted: Boolean) {
        val entry = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) removed(entry)
    }

    private fun removed(entry: FileEntry) {
        _listing.update { it?.copy(entries = it.entries?.filter { e -> e.key != entry.key }) }
        _results.update { r -> r?.filter { it.key != entry.key } }
        say("Deleted ${entry.name}")
    }

    fun say(message: String) {
        _notice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val NOTICE_MS = 3_000L
    }
}
