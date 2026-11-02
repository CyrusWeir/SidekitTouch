package hrln.interactive.sidekit.apps.sideticks

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Tick(val id: Int, val title: String, val ticked: Boolean = false)

/**
 * @property draft text of the tick being added ("My Sideticks Adding Tick", 1:640), or null
 * when not adding.
 */
data class SideticksUiState(
    val ticks: List<Tick> = SideticksSampleData.ticks,
    val showTicked: Boolean = false,
    val draft: String? = null,
) {
    val open: List<Tick> get() = ticks.filterNot { it.ticked }
    val ticked: List<Tick> get() = ticks.filter { it.ticked }
}

class SideticksViewModel : ViewModel() {
    private val _state = MutableStateFlow(SideticksUiState())
    val state: StateFlow<SideticksUiState> = _state.asStateFlow()

    fun toggle(id: Int) = _state.update { state ->
        state.copy(ticks = state.ticks.map { if (it.id == id) it.copy(ticked = !it.ticked) else it })
    }

    fun setShowTicked(show: Boolean) = _state.update { it.copy(showTicked = show) }

    fun clearTicked() = _state.update { state -> state.copy(ticks = state.ticks.filterNot { it.ticked }, showTicked = false) }

    fun startAdding() = _state.update { it.copy(draft = "") }

    fun updateDraft(text: String) = _state.update { if (it.draft == null) it else it.copy(draft = text) }

    fun cancelAdding() = _state.update { it.copy(draft = null) }

    /** Adds the draft to the top of the list unless it's blank, and leaves adding mode. */
    fun confirmDraft() = _state.update { state ->
        val title = state.draft?.trim().orEmpty()
        if (title.isEmpty()) return@update state.copy(draft = null)
        val id = (state.ticks.maxOfOrNull { it.id } ?: 0) + 1
        state.copy(ticks = listOf(Tick(id, title)) + state.ticks, draft = null)
    }
}

object SideticksSampleData {
    // From "My Sideticks No ticks" (1:510) and "show ticked" (1:564).
    val ticks = listOf(
        Tick(1, "Buy Carrots"),
        Tick(2, "Bread"),
        Tick(3, "washing Tn"),
        Tick(4, "Tax No"),
        Tick(5, "Washing Machine", ticked = true),
        Tick(6, "Apples", ticked = true),
    )
}
