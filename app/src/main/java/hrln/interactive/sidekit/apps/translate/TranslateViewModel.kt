package hrln.interactive.sidekit.apps.translate

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The two language/text pairs on the Translate screen, top to bottom. */
enum class TranslateSide { Source, Target }

data class TranslatePane(val language: String, val text: String)

/**
 * @property talking the side whose mic is open ("Voice Mode Talking", 1:837), or null.
 */
data class TranslateUiState(
    val source: TranslatePane = TranslateSampleData.base.first,
    val target: TranslatePane = TranslateSampleData.base.second,
    val talking: TranslateSide? = null,
) {
    fun pane(side: TranslateSide) = if (side == TranslateSide.Source) source else target

    /**
     * Mic tap. Opens that side's mic (closing the other one); tapping the open mic again ends
     * the utterance and shows the placeholder "heard" phrase from "Large prompt" (1:872).
     * There's no real speech recognition or translation.
     */
    fun toggleMic(side: TranslateSide): TranslateUiState = when (talking) {
        side -> {
            val (heardSource, heardTarget) = TranslateSampleData.heard
            copy(source = source.copy(text = heardSource), target = target.copy(text = heardTarget), talking = null)
        }
        else -> copy(talking = side)
    }
}

class TranslateViewModel : ViewModel() {
    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    fun onMic(side: TranslateSide) = _state.update { it.toggleMic(side) }
}

/** Placeholder text copied from the Translate mocks. */
object TranslateSampleData {
    /** "Translate Base Voice Base" (1:910). */
    val base = TranslatePane("English", "Apple") to TranslatePane("Chinese", "苹果")

    /** "Translate Base Large prompt" (1:872). */
    val heard = "Apple Oranges and people too eating cheese and banana" to "苹果橙子和人们也吃奶酪和香蕉"
}
