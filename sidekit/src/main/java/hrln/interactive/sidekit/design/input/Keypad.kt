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

/** The Sidephone's swappable keypad modules. */
enum class KeypadModule(val label: String, val typesOnKeypad: Boolean) {
    /** Phone keypad with D-pad, OK and call keys. */
    T9("T9", true),
    /** Two letters per key, 20 keys. */
    Qwerty("Mini QWERTY", true),
    /** Click wheel with a centre button. */
    Sundial("Sundial", false),
    /** D-pad, A / B / X / Y and a shoulder key. */
    GamePad("Game controller", false),
}

/**
 * The Sidephone's swappable keypad, as seen by text input.
 *
 * The attached [module] is inferred from the keys it sends (see [observe]) and remembered across
 * restarts. Modules with letters ([KeypadModule.typesOnKeypad]: T9, mini QWERTY) never open the
 * virtual keyboard: their presses are typed by the shared [engine] with multi-tap, and its word
 * completions take over the action bar. The Sundial and game controller have no letters, so text
 * fields open the virtual keyboard as usual.
 *
 * A custom mapping ([mapper], Settings > Keypad mapping) can stand in for detection: it fixes the
 * module and rewrites the keys of keypads SideKit doesn't recognise (or keys the user moved) into
 * the module's own, so everything below sees the keys it expects. See [translate].
 */
@Stable
class Keypad internal constructor(
    private val prefs: SharedPreferences?,
    private val userWordsFile: File?,
    private val scope: CoroutineScope,
    private val loadWords: (suspend () -> T9Words)?,
    initialModule: KeypadModule,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
    /** Custom key mapping (off unless the user turns it on in Settings). */
    val mapper: KeyMapper = KeyMapper.forTesting(),
) {
    private var detected: KeypadModule by mutableStateOf(initialModule)

    /** The attached module: the custom mapping's when one is on, else the detected one. */
    val module: KeypadModule get() = mapper.customModule ?: detected

    /** True when text is typed on the keypad itself, so the virtual keyboard never opens. */
    val typing: Boolean get() = module.typesOnKeypad

    /** True while a Sidekit text field (a T9 / QWERTY session) owns the keypad's keys. */
    val isTypingNow: Boolean get() = session != null

    @Volatile private var words: T9Words = T9Words.Empty
    private var wordsJob: Job? = null
    private val userWords = T9UserWords()
    private var saveJob: Job? = null

    /** One engine for the whole system, so mode and case carry across text fields. */
    val engine = T9Engine({ words }, userWords, onLearn = ::scheduleSave)

    /** The focused T9-capable field, if any. */
    internal var session: T9Session? = null
    private data class TypingTarget(val focus: FocusRequester, val activate: () -> Unit)
    private val fallbacks = ArrayList<TypingTarget>()

    init {
        if (typing) loadResources()
    }

    fun useModule(next: KeypadModule) {
        if (detected == next) return
        if (module != (mapper.customModule ?: next)) session?.finish()
        detected = next
        prefs?.edit()?.putString(PREF_MODULE, next.name)?.apply()
        if (typing) loadResources()
    }

    /**
     * Turns the custom mapping on for [custom] (detection pauses, and that module's profile
     * applies), or off with null (back to automatic detection and the keys as they arrive).
     */
    fun useCustomMapping(custom: KeypadModule?) {
        if (mapper.customModule == custom) return
        session?.finish()
        mapper.setCustom(custom)
        if (typing) loadResources()
    }

    /**
     * Applies the custom mapping to a key event as it arrives (see [KeyMapper.translate]). Every
     * place that receives raw keys calls this first and works with what it returns; null means the
     * press is being captured and must be dropped.
     */
    /**
     * Applies the custom mapping to a key event as it arrives (see [KeyMapper.translate]). Every
     * place that receives raw keys calls this first and works with what it returns; null means the
     * press is being captured and must be dropped. This is the raw / custom-only path: typing keys
     * and projection stay untouched, so games and text fields see the keypad exactly as sent.
     */
    fun translate(event: KeyEvent): KeyEvent? = mapper.translate(event)

    /**
     * Translation for Sidekit's own screens ([NavControls]). The custom mapping (if any) applies
     * first and always wins, so a learned key keeps its bound meaning. Defaults apply too, but only
     * while the module is auto-detected and no text field owns the keys: custom mapping is the
     * user's deliberate choice, and a typing key (e.g. QWERTY's modifier keys) must stay as it is
     * while a field is focused.
     */
    fun translateForUi(raw: KeyEvent): KeyEvent? {
        var event = mapper.translate(raw) ?: return null
        if (mapper.customModule == null && session == null) {
            NavControls.applyDefault(detected, event.keyCode)?.let { event = event.withCode(it) }
        }
        return event
    }

    /**
     * Activity-level hook (`dispatchKeyEvent`). Detects the T9 module and, when no field is focused,
     * sends typing keys to the screen's main field so typing starts without touching the screen.
     */
    fun dispatch(event: KeyEvent): Boolean {
        observe(event)
        if (!typing) return false
        session?.let { return it.onKeyEvent(event) }
        val target = fallbacks.lastOrNull()
        if (target != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && isTypingKey(event.keyCode)) {
            target.activate()
            target.focus.requestFocus()
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
        if (event.action != KeyEvent.ACTION_DOWN) return
        // A custom mapping was chosen on purpose: no key may switch it away.
        if (mapper.customModule != null) return
        KeypadDetector.moduleFor(event)?.let(::useModule)
    }

    /** Starts loading the dictionary early (a field gained focus) so the first key is predicted. */
    internal fun prepare() {
        if (typing) loadResources()
    }

    /** Settles any word being typed (before the Options bar takes over, for example). */
    fun finishTyping() {
        session?.finish()
    }

    internal fun registerFallback(focus: FocusRequester, activate: () -> Unit = {}) { fallbacks.add(TypingTarget(focus, activate)) }
    internal fun unregisterFallback(focus: FocusRequester) { fallbacks.removeAll { it.focus === focus } }

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
        private const val PREF_MODULE = "module"
        private const val SAVE_DELAY_MS = 2_000L
        private const val DEFAULT_LONG_PRESS_MS = 450L

        fun isTypingKey(code: Int) = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            code == KeyEvent.KEYCODE_STAR || code == KeyEvent.KEYCODE_POUND || code == KeyEvent.KEYCODE_DEL ||
            code in QwertyLayout.letters || code == KeyEvent.KEYCODE_SPACE

        /** The keypad for this app. The dictionary loads off the main thread the first time T9 is used. */
        fun create(context: Context, scope: CoroutineScope): Keypad {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("sidekit_keypad", Context.MODE_PRIVATE)
            return Keypad(
                prefs = prefs,
                userWordsFile = File(app.filesDir, "t9_user_words.txt"),
                scope = scope,
                loadWords = { app.assets.open("t9/en_words.txt").bufferedReader().use { T9Words.parse(it.readText()) } },
                initialModule = prefs.getString(PREF_MODULE, null)
                    ?.let { name -> KeypadModule.entries.firstOrNull { it.name == name } }
                    // Before modules were told apart, T9 was the only one that typed.
                    ?: if (prefs.getBoolean(PREF_T9, true)) KeypadModule.T9 else KeypadModule.Sundial,
                longPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
                mapper = KeyMapper.create(prefs),
            )
        }

        /** A keypad for previews and tests, with an in-memory dictionary. */
        fun forTesting(
            scope: CoroutineScope,
            words: T9Words,
            module: KeypadModule = KeypadModule.T9,
            mapper: KeyMapper = KeyMapper.forTesting(),
        ) = Keypad(null, null, scope, null, module, mapper = mapper).also { it.words = words }
    }
}

internal fun KeyEvent.withCode(code: Int) =
    KeyEvent(downTime, eventTime, action, code, repeatCount, metaState, deviceId, scanCode, flags, source)

val LocalKeypad = staticCompositionLocalOf<Keypad?> { null }
