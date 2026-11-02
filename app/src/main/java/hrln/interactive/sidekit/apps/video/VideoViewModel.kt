package hrln.interactive.sidekit.apps.video

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Immutable
data class VideoListState(val videos: List<Video> = emptyList(), val loaded: Boolean = false)

/** The video list, re-read when MediaStore changes, and only while the list is on screen. */
class VideoViewModel(
    private val source: VideoSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val state: StateFlow<VideoListState> = source.changes()
        .map { VideoListState(withContext(io) { runCatching { source.videos() }.getOrDefault(emptyList()) }, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoListState())

    /** A video for the player: from the list if it's loaded, else read directly (opened from Photos). */
    suspend fun video(id: Long): Video? =
        state.value.videos.firstOrNull { it.id == id } ?: withContext(io) { runCatching { source.video(id) }.getOrNull() }
}
