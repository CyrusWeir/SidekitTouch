package hrln.interactive.sidekit.design.input

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The Sidephone's swappable keypad, as seen by text input.
 *
 * Every keypad module (T9, click wheel, game pad...) is the same input device (`gxa535_keyboard`)
 * reporting the union of all modules' keys, so which module is attached can't be read from the
 * device. It is inferred from use instead: a digit, `*` or `#` press from hardware means T9, and
 * T9 stays on (remembered across restarts) until a game-pad button shows another module is in.
 *
 * While [t9] is on, text fields don't open the virtual keyboard; keypad presses are typed by the
 * shared [engine] and its word suggestions take over the action bar.
 */
@Stable
class Keypad internal constructor(
    private val prefs: SharedPreferences?,
    private val userWordsFile: File?,
    private val scope: CoroutineScope,
    private val loadWords: (suspend () -> T9Words)?,
    initialT9: Boolean,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    var t9: Boolean by mutableStateOf(initialT9)
        private set

    @Volatile private var words: T9Words = T9Words.Empty
    private var wordsJob: Job? = null
    private val userWords = T9UserWords()
    private var saveJob: Job? = null

    /** One engine for the whole system, so mode and case carry across text fields. */
    val engine = T9Engine({ words }, userWords, onLearn = ::scheduleSave)

    /** The focused T9-capable field, if any. */
    internal var session: T9Session? = null
    private val fallbacks = ArrayList<FocusRequester>()

    init {
        if (initialT9) loadResources()
    }

    fun enableT9(on: Boolean) {
        if (t9 == on) return
        t9 = on
        prefs?.edit()?.putBoolean(PREF_T9, on)?.apply()
        if (on) loadResources()
    }

    /**
     * Activity-level hook (`dispatchKeyEvent`). Detects the T9 module and, when no field is focused,
     * sends typing keys to the screen's main field so typing starts without touching the screen.
     */
    fun dispatch(event: KeyEvent): Boolean {
        observe(event)
        if (!t9) return false
        session?.let { return it.onKeyEvent(event) }
        val target = fallbacks.lastOrNull()
        if (target != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && isTypingKey(event.keyCode)) {
            target.requestFocus()
            return session?.onKeyEvent(event) ?: false
        }
        return false
    }

    /** Infers the attached module from a hardware key event. */
    fun observe(event: KeyEvent) {
        // Virtual keyboards that send key events mark them; everything else is physical. The
        // source isn't checked: remapping tools (e.g. Key Mapper) and injected events can arrive
        // with source 0, while the keypad itself reports keyboard|gamepad.
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return
        when {
            isTypingKey(event.keyCode) && event.keyCode != KeyEvent.KEYCODE_DEL -> enableT9(true)
            KeyEvent.isGamepadButton(event.keyCode) -> enableT9(false)
        }
    }

    /** Starts loading the dictionary early (a field gained focus) so the first key is predicted. */
    internal fun prepare() {
        if (t9) loadResources()
    }

    internal fun registerFallback(focus: FocusRequester) { fallbacks.add(focus) }
    internal fun unregisterFallback(focus: FocusRequester) { fallbacks.remove(focus) }

    private fun loadResources() {
        if (wordsJob != null || loadWords == null) return
        wordsJob = scope.launch {
            val loaded = withContext(Dispatchers.Default) {
                runCatching { loadWords.invoke() }.getOrDefault(T9Words.Empty)
            }
            words = loaded
            // Keys typed while the dictionary was loading get real suggestions now.
            session?.let { engine.refresh(it.buffer) }
            val learned = withContext(Dispatchers.IO) { readUserWords() }
            learned.forEach { (word, count) -> repeat(count.coerceAtMost(50)) { userWords.learn(word) } }
        }
    }

    private fun scheduleSave() {
        val file = userWordsFile ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            val snapshot = userWords.snapshot()
            withContext(Dispatchers.IO) {
                try {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(snapshot.entries.joinToString("\n") { "${it.key}\t${it.value}" })
                    tmp.renameTo(file)
                } catch (_: IOException) {
                    // Learning is best-effort.
                }
            }
        }
    }

    private fun readUserWords(): Map<String, Int> {
        val file = userWordsFile ?: return emptyMap()
        return try {
            file.readLines().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).toIntOrNull() ?: 1)
            }.toMap()
        } catch (_: IOException) {
            emptyMap()
        }
    }

    companion object {
        private const val PREF_T9 = "t9"
        private const val SAVE_DELAY_MS = 2_000L
        private const val DEFAULT_LONG_PRESS_MS = 450L

        fun isTypingKey(code: Int) = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            code == KeyEvent.KEYCODE_STAR || code == KeyEvent.KEYCODE_POUND || code == KeyEvent.KEYCODE_DEL

        /** The keypad for this app. The dictionary loads off the main thread the first time T9 is used. */
        fun create(context: Context, scope: CoroutineScope): Keypad {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("sidekit_keypad", Context.MODE_PRIVATE)
            return Keypad(
                prefs = prefs,
                userWordsFile = File(app.filesDir, "t9_user_words.txt"),
                scope = scope,
                loadWords = { app.assets.open("t9/en_words.txt").bufferedReader().use { T9Words.parse(it.readText()) } },
                initialT9 = prefs.getBoolean(PREF_T9, false),
                longPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
            )
        }

        /** A keypad for previews and tests, with an in-memory dictionary. */
        fun forTesting(scope: CoroutineScope, words: T9Words, t9: Boolean = true) =
            Keypad(null, null, scope, null, t9).also { it.words = words }
    }
}

val LocalKeypad = staticCompositionLocalOf<Keypad?> { null }
