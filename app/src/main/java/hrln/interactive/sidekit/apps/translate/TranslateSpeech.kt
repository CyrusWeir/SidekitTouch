package hrln.interactive.sidekit.apps.translate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.Locale

/** Owns speech only while Translate is visible. Both platform services are optional on AOSP. */
class TranslateSpeech(
    private val context: Context,
    private val onHeard: (TranslateSide, String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: SpeechRequest? = null
    private var listening: TranslateSide? = null

    fun listen(side: TranslateSide, language: TranslateLanguage) {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition isn't installed on this phone")
            return
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(Listener())
                listening = side
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.speechCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
        } catch (_: Exception) {
            releaseRecognizer()
            onError("Couldn't start speech recognition")
        }
    }

    fun stopListening() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        listening = null
    }

    fun speak(request: SpeechRequest) {
        val engine = tts ?: try {
            TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) onError("Text-to-speech isn't installed on this phone")
            }.also { tts = it }
        } catch (_: Exception) {
            onError("Text-to-speech isn't installed on this phone")
            return
        }
        if (!ttsReady) return
        val locale = Locale.forLanguageTag(request.language.speechCode)
        if (engine.setLanguage(locale) == TextToSpeech.LANG_MISSING_DATA) {
            onError("No speech voice for ${request.language.label}")
            return
        }
        engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, "sidekit-translation")
    }

    fun release() {
        releaseRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun releaseRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        listening = null
    }

    private inner class Listener : RecognitionListener {
        override fun onResults(results: Bundle) {
            val side = listening ?: return
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            listening = null
            onHeard(side, text)
        }
        override fun onError(error: Int) {
            listening = null
            if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onError("Didn't hear that. Try again.")
            } else if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onError("Didn't hear anything")
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

@Composable
fun TranslateSpeechEffects(
    vm: TranslateViewModel,
    talking: TranslateSide?,
    permissionNeeded: Boolean,
    sourceLabel: String,
    targetLabel: String,
) {
    val context = LocalContext.current
    val speech = remember { TranslateSpeech(context, vm::onHeard, vm::onSpeechError) }
    LaunchedEffect(talking, permissionNeeded) {
        if (talking == null) speech.stopListening()
        else if (!permissionNeeded) {
            val label = if (talking == TranslateSide.Source) sourceLabel else targetLabel
            val language = TranslateLanguage.entries.first { it.label == label }
            speech.listen(talking, language)
        }
    }
    LaunchedEffect(speech) { vm.speech.collect(speech::speak) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        speech.release()
        vm.stopTalking()
    }
    DisposableEffect(speech) { onDispose { speech.release() } }
}
