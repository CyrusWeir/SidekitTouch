package hrln.interactive.sidekit.design.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/** How keypad keys become text. Long-press `#` switches between them. */
enum class T9Mode { MultiTap, Numeric }

/** Letter case. `#` cycles it. */
enum class T9Case { Sentence, Upper, Lower }

/** Keys of the phone-style (T9) keypad. */
enum class T9Key { D0, D1, D2, D3, D4, D5, D6, D7, D8, D9, Star, Pound, Delete, Enter;

    val digit: Int get() = ordinal.takeIf { it <= 9 } ?: -1
}

/** The text the engine edits. Implemented over a Compose `TextFieldState` in the app. */
interface T9Buffer {
    val text: CharSequence
    val selectionStart: Int
    val selectionEnd: Int

    /** Replaces `[start, end)` with [text] and sets the selection. */
    fun replace(start: Int, end: Int, text: String, selectStart: Int, selectEnd: Int)
}

/**
 * The keypad typing engine, independent of Android so it can be tested on the JVM.
 *
 * Letters are typed by multi-tap: press a key repeatedly to step through its letters (`2` -> a, b,
 * c, 2); the letter settles when another key is pressed or after [TAP_MS]. While a word is being
 * typed, completions of the letters so far go to the action bar ([candidates]): tap one, or move
 * with [moveSelection] and take it with [acceptSelected], to finish the word with a space.
 *
 * Keys follow the printed legends: `0` space, `1` punctuation, `*` symbols, `#` case (`^`), and a
 * long press of `#` toggles numbers. A long press of any digit types the digit.
 *
 * Any key layout can drive it through [tap] (the mini QWERTY passes its own letter groups).
 *
 * Observable state ([mode], [case], [candidates], [selected], [highlight]) is Compose snapshot
 * state, so the action bar recomposes only when it changes.
 */
@Stable
class T9Engine(
    private val words: () -> T9Words,
    private val user: T9UserWords = T9UserWords(),
    private val onLearn: () -> Unit = {},
) {
    var mode by mutableStateOf(T9Mode.MultiTap)
        private set
    var case by mutableStateOf(T9Case.Sentence)
        private set

    /** Completions for the word before the cursor, cased as they'd be typed. Empty when idle. */
    var candidates by mutableStateOf(emptyList<String>())
        private set

    /** The suggestion OK would take. */
    var selected by mutableIntStateOf(0)
        private set

    /** The letter still cycling. The cursor stays collapsed after it, so no selection handles appear. */
    var highlight by mutableStateOf<TextRange?>(null)
        private set

    /** True while a word is being typed and there are suggestions for it. */
    val composing: Boolean get() = candidates.isNotEmpty()

    /** The partial word the suggestions complete, as typed. */
    private var prefix = ""
    private var prefixStart = -1

    private var tapKey = -1
    private var tapIndex = 0
    private var tapAt = 0L
    private var tapPos = -1

    /** Short label for the current mode and case, for the action bar. */
    val modeLabel: String
        get() = when (mode) {
            T9Mode.Numeric -> "123"
            T9Mode.MultiTap -> when (case) {
                T9Case.Sentence -> "Abc"
                T9Case.Upper -> "ABC"
                T9Case.Lower -> "abc"
            }
        }

    /** Handles one phone-keypad key. [long] is a long press. Returns false if the key isn't for typing. */
    fun key(key: T9Key, buf: T9Buffer, now: Long, long: Boolean = false): Boolean {
        dropIfMoved(buf)
        when {
            key == T9Key.Pound && long -> toggleNumeric(buf)
            key == T9Key.Pound -> { finishTap(); cycleCase(); suggest(buf) }
            long && key.digit >= 0 && mode == T9Mode.MultiTap -> { finishTap(); insert(buf, key.digit.toString()); suggest(buf) }
            key == T9Key.Delete -> { finishTap(); deleteBack(buf); suggest(buf) }
            key == T9Key.Enter -> { finish(buf); insert(buf, "\n") }
            mode == T9Mode.Numeric -> {
                finishTap()
                insert(buf, if (key == T9Key.Star) "*" else key.digit.toString())
            }
            key == T9Key.Star -> tap(STAR_ID, SYMBOLS, buf, now)
            else -> tap(key.digit, MULTI_TAP[key.digit], buf, now)
        }
        return true
    }

    /**
     * One press of a multi-tap key [id] whose characters are [letters]: a repeat press within
     * [TAP_MS] steps to the next character, otherwise the first character is typed.
     */
    fun tap(id: Int, letters: String, buf: T9Buffer, now: Long) {
        dropIfMoved(buf)
        if (letters.isEmpty()) return
        // A key with one letter (the QWERTY's L, M) types it again each press, so "ll" just works.
        if (letters.length > 1 && tapPos >= 0 && tapKey == id && now - tapAt < TAP_MS) {
            tapIndex = (tapIndex + 1) % letters.length
            val c = caseChar(letters[tapIndex], buf.text, tapPos).toString()
            buf.replace(tapPos, tapPos + 1, c, tapPos + 1, tapPos + 1)
        } else {
            finishTap()
            var s = buf.selectionStart
            val c = letters[0]
            // A word ends when a space or punctuation follows it: learn it.
            if (!c.isLetter() && c != '\'') learnWordBefore(buf, s)
            // "hello ." reads as a typo (suggestions end words with a space): attach the punctuation.
            val text = buf.text
            if (c in ATTACHING && s >= 2 && buf.selectionEnd == s && text[s - 1] == ' ' && text[s - 2].isLetterOrDigit()) {
                buf.replace(s - 1, s, "", s - 1, s - 1)
                s -= 1
            }
            buf.replace(s, buf.selectionEnd, caseChar(c, buf.text, s).toString(), s + 1, s + 1)
            tapPos = s
            tapIndex = 0
            tapKey = id
        }
        tapAt = now
        highlight = TextRange(tapPos, tapPos + 1)
        suggest(buf)
    }

    /** Types [s] as-is (a key with a single character, or a long-pressed digit). */
    fun type(s: String, buf: T9Buffer) {
        dropIfMoved(buf)
        finishTap()
        if (s.any { !it.isLetter() && it != '\'' }) learnWordBefore(buf, buf.selectionStart)
        insert(buf, s)
        suggest(buf)
    }

    /** Deletes the character before the cursor (or the selection). */
    fun delete(buf: T9Buffer) {
        dropIfMoved(buf)
        finishTap()
        deleteBack(buf)
        suggest(buf)
    }

    /** Takes suggestion [index] (tapped in the action bar) in place of the partial word, then a space. */
    fun pick(index: Int, buf: T9Buffer) {
        if (index !in candidates.indices || prefixStart < 0) return
        val word = candidates[index]
        finishTap()
        val end = buf.selectionEnd
        val text = buf.text
        val spaceNext = end < text.length && text[end] == ' '
        val insert = if (spaceNext) word else "$word "
        val cursor = prefixStart + word.length + 1
        buf.replace(prefixStart, end, insert, cursor, cursor)
        learn(word)
        clearSuggestions()
    }

    /** Moves the highlighted suggestion by [delta], wrapping. Returns false when there are none. */
    fun moveSelection(delta: Int): Boolean {
        if (candidates.isEmpty()) return false
        selected = Math.floorMod(selected + delta, candidates.size)
        return true
    }

    /**
     * OK while typing a word: takes the highlighted suggestion. Returns false (and just settles
     * the word) when that suggestion is exactly what's typed, so OK can do its usual job.
     */
    fun acceptSelected(buf: T9Buffer): Boolean {
        dropIfMoved(buf)
        if (candidates.isEmpty()) return false
        if (candidates[selected] == prefix) {
            finish(buf)
            return false
        }
        pick(selected, buf)
        return true
    }

    fun setMode(next: T9Mode, buf: T9Buffer) {
        finish(buf)
        mode = next
    }

    /** Ends any pending multi-tap letter and hides the suggestions, keeping the text. */
    fun finish(buf: T9Buffer) {
        if (tapPos >= 0) learnWordBefore(buf, buf.selectionStart)
        finishTap()
        clearSuggestions()
    }

    /** Recomputes suggestions (e.g. once the dictionary has finished loading). */
    fun refresh(buf: T9Buffer) {
        dropIfMoved(buf)
        if (composing || tapPos >= 0) suggest(buf)
    }

    /** Called periodically: settles a multi-tap letter once its key hasn't been pressed for a moment. */
    fun tick(buf: T9Buffer, now: Long) {
        if (tapPos >= 0 && now - tapAt >= TAP_MS) finishTap()
    }

    val pendingTapDeadline: Long get() = if (tapPos >= 0) tapAt + TAP_MS else -1

    /** If the cursor was moved by touch since the last key, start fresh around the new position. */
    private fun dropIfMoved(buf: T9Buffer) {
        if (tapPos >= 0 && (buf.selectionStart != buf.selectionEnd || buf.selectionEnd != tapPos + 1)) finishTap()
        if (prefixStart >= 0 && (buf.selectionStart != buf.selectionEnd || buf.selectionEnd != prefixStart + prefix.length)) {
            clearSuggestions()
        }
    }

    private fun finishTap() {
        tapPos = -1
        tapKey = -1
        highlight = null
    }

    private fun toggleNumeric(buf: T9Buffer) {
        finish(buf)
        mode = if (mode == T9Mode.Numeric) T9Mode.MultiTap else T9Mode.Numeric
    }

    // ---- Suggestions ----

    private fun suggest(buf: T9Buffer) {
        val end = buf.selectionEnd
        val text = buf.text
        if (mode != T9Mode.MultiTap || buf.selectionStart != end || (end < text.length && text[end].isLetter())) {
            clearSuggestions()
            return
        }
        var start = end
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '\'')) start--
        if (start == end || !text[start].isLetter()) {
            clearSuggestions()
            return
        }
        val typed = text.substring(start, end)
        val list = completions(typed)
        prefix = typed
        prefixStart = start
        if (list != candidates) {
            candidates = list
            selected = 0
        }
    }

    private fun completions(typed: String): List<String> {
        val key = typed.lowercase().replace("'", "")
        if (key.isEmpty() || key.any { it !in 'a'..'z' }) return emptyList()
        val dict = words().complete(key, MAX_CANDIDATES * 2).map { CONTRACTIONS[it] ?: it }
        val learned = user.complete(key)
        val merged = LinkedHashSet<String>()
        // What's typed comes first when it's already a word, so OK keeps it as typed ("dont" offers
        // "don't" first instead).
        val typedLower = typed.lowercase()
        val contraction = CONTRACTIONS[key]
        if (contraction != null) merged.add(contraction)
        else if (user.count(typedLower) > 0 || isCommonWord(key, dict.firstOrNull())) merged.add(typedLower)
        // Words used more than once outrank the dictionary; a word used once follows its best match.
        learned.filter { user.count(it) >= 2 }.forEach(merged::add)
        dict.firstOrNull()?.let(merged::add)
        learned.forEach(merged::add)
        dict.forEach(merged::add)
        return merged.filter { it.length >= typed.length }
            .take(MAX_CANDIDATES)
            .map { matchCase(it, typed) }
    }

    /**
     * Whether [word] is a real, common word rather than a fragment the subtitle-derived list
     * happens to contain ("hel", "th"): common outright, or about as common as [best], the top
     * completion.
     */
    private fun isCommonWord(word: String, best: String?): Boolean {
        val rank = words().rank(word)
        if (rank < 0) return false
        if (rank < COMMON_RANK) return true
        val bestRank = best?.let { words().rank(it.replace("'", "")) } ?: return true
        return bestRank < 0 || rank <= bestRank * 3
    }

    /** Cases [word] to continue [typed]: ALL CAPS stays caps, a capital first letter stays. */
    private fun matchCase(word: String, typed: String): String {
        val upper = case == T9Case.Upper || (typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() })
        return when {
            upper -> word.uppercase()
            word == "i" || word.startsWith("i'") -> "I" + word.substring(1)
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    private fun clearSuggestions() {
        prefix = ""
        prefixStart = -1
        if (candidates.isNotEmpty()) candidates = emptyList()
        selected = 0
    }

    // ---- Shared ----

    private fun learnWordBefore(buf: T9Buffer, pos: Int) {
        val text = buf.text
        var i = pos
        while (i > 0 && (text[i - 1].isLetter() || text[i - 1] == '\'')) i--
        if (pos - i >= 2) learn(text.substring(i, pos))
    }

    private fun cycleCase() {
        case = when (case) {
            T9Case.Sentence -> T9Case.Upper
            T9Case.Upper -> T9Case.Lower
            T9Case.Lower -> T9Case.Sentence
        }
    }

    private fun insert(buf: T9Buffer, s: String) {
        val start = buf.selectionStart
        buf.replace(start, buf.selectionEnd, s, start + s.length, start + s.length)
    }

    private fun deleteBack(buf: T9Buffer) {
        val s = buf.selectionStart
        val e = buf.selectionEnd
        if (s != e) {
            buf.replace(s, e, "", s, s)
        } else if (s > 0) {
            val from = if (s >= 2 && buf.text[s - 1].isLowSurrogate() && buf.text[s - 2].isHighSurrogate()) s - 2 else s - 1
            buf.replace(from, s, "", from, from)
        }
    }

    private fun learn(word: String) {
        user.learn(word)
        onLearn()
    }

    private fun caseChar(c: Char, text: CharSequence, at: Int): Char = when (case) {
        T9Case.Upper -> c.uppercaseChar()
        T9Case.Lower -> c
        T9Case.Sentence -> if (sentenceStart(text, at)) c.uppercaseChar() else c
    }

    companion object {
        const val TAP_MS = 900L
        const val MAX_CANDIDATES = 3
        private const val COMMON_RANK = 3000
        private const val STAR_ID = 100
        private const val ATTACHING = ".,?!:;"
        val MULTI_TAP = arrayOf(" 0", ".,?!'-1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9")
        const val SYMBOLS = "*+-=/@#&%\$()"

        /**
         * The word list has no apostrophes; these are suggested with theirs. Spellings that are
         * also words on their own ("its", "lets", "well", "ill") are left alone.
         */
        val CONTRACTIONS = listOf(
            "don't", "i'm", "i've", "can't", "won't", "isn't", "that's", "you're", "didn't", "doesn't",
            "he's", "she's", "they're", "wasn't", "aren't", "couldn't", "wouldn't", "shouldn't",
            "haven't", "hasn't", "you've", "you'll", "we've", "they've", "what's", "there's", "where's",
            "hadn't", "weren't", "ain't", "who's", "they'll", "y'all",
        ).associateBy { it.replace("'", "") }

        /** True when position [at] starts a sentence: the start of text, or after `. ! ?` or a newline. */
        fun sentenceStart(text: CharSequence, at: Int): Boolean {
            var i = at - 1
            while (i >= 0 && text[i] == ' ') i--
            if (i < 0) return true
            val c = text[i]
            return c == '.' || c == '!' || c == '?' || c == '\n'
        }
    }
}
