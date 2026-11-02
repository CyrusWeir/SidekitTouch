package hrln.interactive.sidekit.apps.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two language/text pairs on the Translate screen, top to bottom. */
enum class TranslateSide { Source, Target }
data class TranslatePane(val language: String, val text: String)
enum class TranslateMode { Translate, History, Large }
data class SpeechRequest(val text: String, val language: TranslateLanguage)

data class TranslateUiState(
    val source: TranslatePane = TranslatePane(TranslateLanguage.English.label, ""),
    val target: TranslatePane = TranslatePane(TranslateLanguage.Chinese.label, ""),
    val talking: TranslateSide? = null,
    val translating: Boolean = false,
    val mode: TranslateMode = TranslateMode.Translate,
    val error: String? = null,
    val microphonePermissionNeeded: Boolean = false,
) {
    fun pane(side: TranslateSide) = if (side == TranslateSide.Source) source else target
    fun toggleMic(side: TranslateSide): TranslateUiState = copy(talking = if (talking == side) null else side, error = null)
}

class TranslateViewModel(
    private val repository: TranslateRepository,
    private val translator: Translator,
) : ViewModel() {
    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()
    val history: StateFlow<List<Translation>?> = repository.history
    private val _speech = MutableSharedFlow<SpeechRequest>(extraBufferCapacity = 1)
    val speech: SharedFlow<SpeechRequest> = _speech.asSharedFlow()
    private var translateJob: Job? = null
    private var sourceLanguage = TranslateLanguage.English
    private var targetLanguage = TranslateLanguage.Chinese
    private var errorJob: Job? = null

    fun onMic(side: TranslateSide) {
        val wasTalking = _state.value.talking == side
        _state.update { it.toggleMic(side) }
        if (!wasTalking && _state.value.talking == side) {
            _state.update { it.copy(microphonePermissionNeeded = true) }
        }
    }

    fun microphonePermissionResult(granted: Boolean) {
        _state.update { it.copy(microphonePermissionNeeded = false) }
        if (!granted) {
            _state.update { it.copy(talking = null) }
            say("Microphone permission is needed to listen")
        }
    }

    fun onHeard(side: TranslateSide, text: String) {
        if (text.isBlank()) return
        if (side == TranslateSide.Source) {
            _state.update { it.copy(source = it.source.copy(text = text), error = null) }
            translate()
        } else {
            translate(text, targetLanguage, sourceLanguage, reverse = true)
        }
    }

    fun onSpeechError(message: String) {
        _state.update { it.copy(talking = null) }
        say(message)
    }

    fun stopTalking() { _state.update { it.copy(talking = null) } }

    fun setText(text: String) {
        _state.update { it.copy(source = it.source.copy(text = text.take(MyMemoryTranslator.MAX_CHARS)), error = null) }
    }

    fun translate() {
        val text = _state.value.source.text.trim()
        if (text.isEmpty()) { say("Type something to translate"); return }
        translate(text, sourceLanguage, targetLanguage, reverse = false)
    }

    private fun translate(text: String, from: TranslateLanguage, to: TranslateLanguage, reverse: Boolean) {
        translateJob?.cancel()
        _state.update { it.copy(translating = true, error = null) }
        translateJob = viewModelScope.launch {
            try {
                val result = translator.translate(text, from, to)
                repository.add(text, result, from, to)
                _state.update {
                    if (reverse) it.copy(source = it.source.copy(text = result), target = it.target.copy(text = text), translating = false)
                    else it.copy(target = it.target.copy(text = result), translating = false)
                }
                if (_state.value.talking != null) _speech.tryEmit(SpeechRequest(result, to))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(translating = false) }
                say("Translation failed. Check the connection and try again.")
            }
        }
    }

    fun cancelTranslation() {
        translateJob?.cancel()
        _state.update { it.copy(translating = false) }
    }

    fun swapLanguages() {
        val oldSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = oldSource
        _state.update {
            it.copy(
                source = TranslatePane(sourceLanguage.label, it.target.text),
                target = TranslatePane(targetLanguage.label, it.source.text),
            )
        }
    }

    fun nextLanguage(side: TranslateSide) {
        val languages = TranslateLanguage.entries
        if (side == TranslateSide.Source) {
            sourceLanguage = languages[(languages.indexOf(sourceLanguage) + 1).mod(languages.size)]
            if (sourceLanguage == targetLanguage) targetLanguage = languages[(languages.indexOf(sourceLanguage) + 1).mod(languages.size)]
        } else {
            targetLanguage = languages[(languages.indexOf(targetLanguage) + 1).mod(languages.size)]
            if (targetLanguage == sourceLanguage) sourceLanguage = languages[(languages.indexOf(targetLanguage) + 1).mod(languages.size)]
        }
        _state.update { it.copy(source = it.source.copy(language = sourceLanguage.label), target = it.target.copy(language = targetLanguage.label)) }
    }

    fun clear() {
        cancelTranslation()
        _state.update { it.copy(source = it.source.copy(text = ""), target = it.target.copy(text = ""), error = null) }
    }

    fun show(mode: TranslateMode) { _state.update { it.copy(mode = mode) } }
    fun open(entry: Translation) {
        sourceLanguage = entry.source
        targetLanguage = entry.target
        _state.update {
            it.copy(
                source = TranslatePane(entry.source.label, entry.sourceText),
                target = TranslatePane(entry.target.label, entry.translatedText),
                mode = TranslateMode.Translate,
            )
        }
    }

    fun remove(id: Long) = repository.remove(id)
    fun clearHistory() = repository.clear()
    fun speak(text: String, side: TranslateSide) {
        if (text.isNotBlank()) _speech.tryEmit(SpeechRequest(text, if (side == TranslateSide.Source) sourceLanguage else targetLanguage))
    }

    fun say(message: String) {
        errorJob?.cancel()
        _state.update { it.copy(error = message) }
        errorJob = viewModelScope.launch { delay(3_000); _state.update { it.copy(error = null) } }
    }

    fun dismissError() {
        errorJob?.cancel()
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        translateJob?.cancel()
        errorJob?.cancel()
    }
}

/** Sample content is used only by previews and screenshot tests, never as translation output. */
object TranslateSampleData {
    val base = TranslatePane("English", "Apple") to TranslatePane("Chinese", "苹果")
    val heard = "Apple Oranges and people too eating cheese and banana" to "苹果橙子和人们也吃奶酪和香蕉"
    fun state(talking: TranslateSide? = null) = TranslateUiState(base.first, base.second, talking)
}
