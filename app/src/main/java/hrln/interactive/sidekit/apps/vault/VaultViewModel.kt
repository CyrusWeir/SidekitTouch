package hrln.interactive.sidekit.apps.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class VaultState(
    val unlocked: Boolean = false,
    val notes: List<PrivateNote> = emptyList(),
    val editing: PrivateNote? = null,
    val busy: Boolean = false,
    val message: String? = null,
)
class VaultViewModel(private val store: VaultStore, private val io: CoroutineDispatcher = Dispatchers.IO, private val timeoutMs: Long = 55_000) : ViewModel() {
    private val mutable = MutableStateFlow(VaultState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0
    private var expiry: Job? = null
    fun lock() {
        generation++
        expiry?.cancel()
        mutable.value = VaultState()
    }
    fun error(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun authenticated() {
        val token = ++generation
        mutable.value = VaultState(busy = true)
        expiry?.cancel()
        expiry = viewModelScope.launch { delay(timeoutMs); lock() }
        viewModelScope.launch {
            try {
                val notes = withContext(io) { mutex.withLock { store.read() } }
                if (token == generation) mutable.value = VaultState(unlocked = true, notes = notes)
            } catch (e: Exception) {
                if (token == generation) mutable.value = VaultState(message = "Cannot unlock: ${e.localizedMessage}. Existing encrypted data has been kept.")
            }
        }
    }
    fun edit(note: PrivateNote? = null) {
        if (mutable.value.unlocked && !mutable.value.busy) mutable.value = mutable.value.copy(editing = note ?: PrivateNote(UUID.randomUUID().toString(), ""), message = null)
    }
    fun text(body: String) { val note = mutable.value.editing ?: return; mutable.value = mutable.value.copy(editing = note.copy(body = body)) }
    fun closeEditor() { mutable.value = mutable.value.copy(editing = null, message = null) }
    fun save() {
        val current = mutable.value
        val note = current.editing ?: return
        if (note.body.isBlank()) { error("Type a note before saving."); return }
        persist(listOf(note) + current.notes.filter { it.id != note.id })
    }
    fun delete(note: PrivateNote) = persist(mutable.value.notes.filter { it.id != note.id })
    private fun persist(notes: List<PrivateNote>) {
        if (!mutable.value.unlocked || mutable.value.busy) return
        val token = generation
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                withContext(io) { mutex.withLock { store.write(notes) } }
                if (token == generation) mutable.value = mutable.value.copy(notes = notes, editing = null, busy = false, message = "Saved encrypted")
            } catch (e: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(busy = false, message = "Not saved: ${e.localizedMessage}. Unlock again if authentication expired.")
            }
        }
    }
}
