package hrln.interactive.sidekit.design.input

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Speech to text for a text field, through the platform recognizer (absent on some AOSP builds).
 * Each [start] listens for one phrase and hands back what was heard; the recognizer is only held
 * while listening. Problems are shown as a toast: a text field has nowhere of its own to say so.
 */
@Stable
class Dictation internal constructor(private val context: Context) {
    /** True from [start] until a phrase or an error comes back. */
    var listening by mutableStateOf(false)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var onHeard: ((String) -> Unit)? = null

    fun start(onHeard: (String) -> Unit) {
        release()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            tell("Speech recognition isn't installed on this phone")
            return
        }
        this.onHeard = onHeard
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(Listener())
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
            listening = true
        } catch (_: Exception) {
            release()
            tell("Couldn't start dictation")
        }
    }

    /** Stops listening; what was heard so far still arrives. */
    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    fun release() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        onHeard = null
        listening = false
    }

    private fun tell(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private inner class Listener : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val deliver = onHeard
            release()
            if (text.isNotBlank()) deliver?.invoke(text)
        }
        override fun onError(error: Int) {
            release()
            when (error) {
                // Our own cancel() destroys the recogniser first, so this is a real start failure.
                SpeechRecognizer.ERROR_CLIENT -> tell("Couldn't start dictation")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> tell("Dictation is busy. Try again.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> tell("Dictation isn't available in this language")
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> tell("Didn't hear anything")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tell("Allow the microphone to dictate")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tell("Dictation needs a connection")
                else -> tell("Didn't catch that. Try again.")
            }
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** A [Dictation] that stops when the screen leaves or the app goes to the background. */
@Composable
fun rememberDictation(): Dictation {
    val context = LocalContext.current
    val dictation = remember(context) { Dictation(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dictation.release() }
    DisposableEffect(dictation) { onDispose { dictation.release() } }
    return dictation
}

/** Puts [heard] at the cursor (replacing any selection), spaced from the word before it. */
internal fun TextFieldState.insertDictated(heard: String) {
    val text = heard.trim()
    if (text.isEmpty()) return
    edit {
        val start = selection.min
        val end = selection.max
        val spaced = if (start > 0 && !asCharSequence()[start - 1].isWhitespace()) " $text" else text
        replace(start, end, spaced)
        selection = TextRange(start + spaced.length)
    }
}
