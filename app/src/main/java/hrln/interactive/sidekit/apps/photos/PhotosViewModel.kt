package hrln.interactive.sidekit.apps.photos

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One grid's worth of the library, loaded a page at a time. */
@Immutable
data class GridState(
    val items: List<MediaItem> = emptyList(),
    /** False until the first page has been read; the grid shows nothing rather than "empty". */
    val loaded: Boolean = false,
    val endReached: Boolean = false,
)

@Immutable
data class AlbumsState(val folders: List<PhotoAlbum> = emptyList(), val loaded: Boolean = false)

/**
 * Pages through one album. The first page is read when the grid is first watched; [loadMore]
 * appends the next as the grid nears its end. A MediaStore change (photo taken, deleted) reloads
 * everything loaded so far in one query, keeping the user's place.
 *
 * MediaStore is only watched while someone collects [state] (`WhileSubscribed`), so a grid that
 * isn't on screen costs nothing.
 */
internal class GridLoader(
    private val album: AlbumKey,
    private val source: PhotoSource,
    private val favourites: StateFlow<Set<Long>?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val current = MutableStateFlow(GridState())
    private val mutex = Mutex()

    val state: StateFlow<GridState> = channelFlow {
        val triggers = if (album == AlbumKey.Favourites) {
            combine(source.changes(), favourites.filterNotNull().distinctUntilChanged()) { _, _ -> }
        } else {
            source.changes()
        }
        launch { triggers.collect { reload() } }
        current.collect { send(it) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), current.value)

    val value: GridState get() = current.value

    private suspend fun reload() = mutex.withLock {
        val count = maxOf(PAGE, current.value.items.size)
        val items = read(0, count)
        current.value = GridState(items, loaded = true, endReached = items.size < count)
    }

    fun loadMore() {
        val now = current.value
        if (!now.loaded || now.endReached || mutex.isLocked) return
        scope.launch {
            mutex.withLock {
                val have = current.value
                if (have.endReached) return@withLock
                val more = read(have.items.size, PAGE)
                // Drop any overlap if a photo was added at the top since the last page.
                val seen = have.items.mapTo(HashSet(have.items.size)) { it.id }
                current.value = have.copy(items = have.items + more.filter { it.id !in seen }, endReached = more.size < PAGE)
            }
        }
    }

    fun forceReload() {
        scope.launch { reload() }
    }

    private suspend fun read(offset: Int, limit: Int): List<MediaItem> = withContext(io) {
        runCatching { source.page(album, favourites.value.orEmpty(), offset, limit) }.getOrDefault(emptyList())
    }

    companion object {
        /** 30 rows of four: a few screens ahead, ~2 ms per page to read. */
        const val PAGE = 120
    }
}

/**
 * State for Photos, shared by its screens. Grids are kept per album for as long as the visit
 * lasts, so the viewer pages through exactly the list the user tapped into.
 */
class PhotosViewModel(
    private val source: PhotoSource,
    private val favourites: FavouritesRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val grids = HashMap<AlbumKey, GridLoader>()

    private fun loader(album: AlbumKey): GridLoader =
        grids.getOrPut(album) { GridLoader(album, source, favourites.ids, viewModelScope, io) }

    fun grid(album: AlbumKey): StateFlow<GridState> = loader(album).state

    fun loadMore(album: AlbumKey) = loader(album).loadMore()

    val favouriteIds: StateFlow<Set<Long>> = favourites.ids.filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), favourites.ids.value.orEmpty())

    val albums: StateFlow<AlbumsState> = channelFlow {
        source.changes().collect {
            send(AlbumsState(withContext(io) { runCatching { source.folders() }.getOrDefault(emptyList()) }, loaded = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumsState())

    fun toggleFavourite(item: MediaItem) = favourites.toggle(item.id)

    /** Called once the system has deleted [item] (the user confirmed MediaStore's prompt). */
    fun onDeleted(item: MediaItem) {
        favourites.remove(item.id)
        grids.values.forEach { it.forceReload() }
    }

    /** Index of [id] in [album]'s loaded items, or 0. */
    fun indexOf(album: AlbumKey, id: Long): Int = loader(album).value.items.indexOfFirst { it.id == id }.coerceAtLeast(0)
}

/** Album names for the Albums screen's two-up tiles. */
val smartAlbums: List<PhotoAlbum> = AlbumKey.smart.map { PhotoAlbum(it, it.smartName()!!) }
