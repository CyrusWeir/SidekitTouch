package hrln.interactive.sidekit.apps.banking

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

/** Something just deleted, offered back by an Undo split in the bar for a few seconds. */
data class BankingUndo(val label: String, val removed: BankBook)

data class BankingUiState(
    val load: BankingRepository.Load = BankingRepository.Load.Locked,
    val undo: BankingUndo? = null,
) {
    val book: BankBook? get() = (load as? BankingRepository.Load.Open)?.book
}

class BankingViewModel(
    private val repository: BankingRepository,
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
) : ViewModel() {
    private val undo = MutableStateFlow<BankingUndo?>(null)
    private var undoJob: Job? = null

    val state: StateFlow<BankingUiState> = combine(repository.state, undo) { load, u -> BankingUiState(load, u) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BankingUiState(repository.state.value))

    val connector: BankConnector get() = repository.connector

    /** Call once the user has passed the screen lock (or the phone has none). */
    fun unlock() {
        viewModelScope.launch { repository.unlock() }
    }

    fun lock() {
        undoJob?.cancel()
        undo.value = null
        repository.lock()
    }

    fun eraseUnreadable() = repository.eraseUnreadable()

    fun addAccount(name: String, openingCents: Long): Long? = repository.addAccount(name, openingCents)

    fun editAccount(id: Long, name: String, openingCents: Long) = repository.renameAccount(id, name, openingCents)

    fun selectAccount(id: Long) = repository.selectAccount(id)

    fun record(accountId: Long, payee: String, cents: Long, note: String, incoming: Boolean): Long? =
        repository.record(accountId, payee, cents, note, incoming)

    fun deleteAccount(id: Long) {
        val removed = repository.deleteAccount(id) ?: return
        offerUndo(BankingUndo("Undo delete ${removed.accounts.first().name}", removed))
    }

    fun deleteTransaction(id: Long) {
        val t = repository.deleteTransaction(id) ?: return
        offerUndo(BankingUndo("Undo delete ${t.payee}", BankBook(transactions = listOf(t))))
    }

    fun undo() {
        val u = undo.value ?: return
        undoJob?.cancel()
        repository.restore(u.removed)
        undo.value = null
    }

    private fun offerUndo(u: BankingUndo) {
        undo.value = u
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(undoWindowMs)
            undo.value = null
        }
    }

    /** Leaving Banking locks it: the decrypted book doesn't outlive the visit. */
    override fun onCleared() = repository.lock()

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
