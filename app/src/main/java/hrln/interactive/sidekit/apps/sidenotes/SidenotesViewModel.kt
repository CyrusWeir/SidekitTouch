package hrln.interactive.sidekit.apps.sidenotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SidenotesUiState(
    val notes: List<Note> = emptyList(),
    /** False until the notes file has been read; the list shows nothing rather than "empty". */
    val loaded: Boolean = true,
    /** Set for a few seconds after a delete, while the action bar offers Undo. */
    val recentlyDeleted: Note? = null,
)

/** Id used for a note that hasn't been saved yet. */
const val NEW_NOTE_ID = -1L

class SidenotesViewModel(
    private val repository: NotesRepository,
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
) : ViewModel() {
    private val recentlyDeleted = MutableStateFlow<Note?>(null)
    private var undoJob: Job? = null

    val state: StateFlow<SidenotesUiState> = combine(repository.notes, recentlyDeleted) { notes, deleted ->
        SidenotesUiState(notes.orEmpty(), notes != null, deleted)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SidenotesUiState(repository.notes.value.orEmpty(), repository.notes.value != null, null),
    )

    fun note(id: Long): Note? = repository.get(id)

    /** Saves [body] to note [id] (or a new note). Returns the note's id, or [NEW_NOTE_ID] if nothing was saved. */
    fun save(id: Long, body: String): Long = repository.save(id.takeIf { it != NEW_NOTE_ID }, body) ?: NEW_NOTE_ID

    /**
     * Called when leaving the editor. Saves changes; an existing note emptied by the user is
     * deleted with the usual undo, like clearing a note in iOS Notes.
     */
    fun close(id: Long, body: String) {
        if (body.isBlank()) {
            if (id != NEW_NOTE_ID) delete(id)
        } else {
            save(id, body)
        }
    }

    fun delete(id: Long) {
        val note = repository.delete(id) ?: return
        recentlyDeleted.value = note
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(undoWindowMs)
            recentlyDeleted.value = null
        }
    }

    fun undoDelete() {
        val note = recentlyDeleted.value ?: return
        undoJob?.cancel()
        repository.restore(note)
        recentlyDeleted.value = null
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}

object SidenotesSampleData {
    private const val T = 1_760_000_000_000L

    // Placeholder content from "My Notes multiple notes" (1:278) and "Existing Note" (1:312).
    val notes = listOf(
        Note(
            5,
            "Note #1\nI was over at the park yesterday so soon I guess I should leave.\nGet more milk tomorrow I really eed some.\n" +
                "Tomorrow grab new shit from the new shop. and then I will have to go over to my brother",
            T + 5,
        ),
        Note(4, "Tax No\n24587087607670", T + 4),
        Note(3, "Note #2\nDont forget to go over to the", T + 3),
        Note(2, "Tax No\n24587087607670", T + 2),
        Note(1, "Tax No\n24587087607670", T + 1),
    )
}
