package hrln.interactive.sidekit.design.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/** How keypad digits become text. `#` cycles through them. */
enum class T9Mode { Predictive, MultiTap, Numeric }

/** Letter case. `*` cycles it when no word is being composed. */
enum class T9Case { Sentence, Upper, Lower }

/** Keys the engine understands. */
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
 * A T9 keypad typing engine, independent of Android so it can be tested on the JVM.
 *
 * Predictive mode (default, like Nokia T9): each digit narrows the word; the best match is written
 * inline, highlighted, while the alternatives go to the action bar. `0` commits with a space, `*`
 * cycles alternatives, `1` is punctuation (or an apostrophe mid-word: "i'm" is 4-1-6). Multi-tap
 * presses a key repeatedly for each letter. Numeric types digits. Long-pressing a digit types the
 * digit in any mode.
 *
 * Observable state ([mode], [case], [candidates], [selected], [composing]) is Compose snapshot
 * state, so the action bar recomposes only when it changes.
 */
@Stable
class T9Engine(
    private val words: () -> T9Words,
    private val user: T9UserWords = T9UserWords(),
    private val onLearn: () -> Unit = {},
) {
    var mode by mutableStateOf(T9Mode.Predictive)
        private set
    var case by mutableStateOf(T9Case.Sentence)
        private set

    /** Suggestions for the word being composed, cased as they'd be typed. Empty when idle. */
    var candidates by mutableStateOf(emptyList<String>())
        private set
    var selected by mutableIntStateOf(0)
        private set

    /**
     * The text to highlight: the word being composed, or the multi-tap letter still cycling. The
     * cursor itself stays collapsed after it, so no selection handles appear.
     */
    var highlight by mutableStateOf<TextRange?>(null)
        private set

    /** True when no dictionary word matches the keys typed; [candidates] holds a best guess. */
    var unknown by mutableStateOf(false)
        private set

    private var composeStartState = mutableIntStateOf(-1)
    private var composeStart: Int
        get() = composeStartState.intValue
        set(value) { composeStartState.intValue = value }
    val composing: Boolean get() = composeStart >= 0

    private val digits = StringBuilder()
    private var raw: List<String> = emptyList()
    private var composeLen = 0

    private var tapKey = -1
    private var tapIndex = 0
    private var tapAt = 0L
    private var tapPos = -1

    private var punctIndex = -1
    private var punctAt = 0L
    private var punctPos = -1

    /** Short label for the current mode and case, for the action bar. */
    val modeLabel: String
        get() = when (mode) {
            T9Mode.Numeric -> "123"
            else -> {
                val letters = when (case) {
                    T9Case.Sentence -> "Abc"
                    T9Case.Upper -> "ABC"
                    T9Case.Lower -> "abc"
                }
                if (mode == T9Mode.Predictive) "T9 $letters" else letters
            }
        }

    /** Handles one key press. [long] is a long press. Returns false if the key isn't for typing. */
    fun key(key: T9Key, buf: T9Buffer, now: Long, long: Boolean = false): Boolean {
        dropIfMoved(buf)
        if (key == T9Key.Pound) {
            if (long) return true
            finish(buf)
            mode = T9Mode.entries[(mode.ordinal + 1) % T9Mode.entries.size]
            return true
        }
        if (long && key.digit >= 0 && mode != T9Mode.Numeric) {
            finish(buf)
            insert(buf, key.digit.toString())
            return true
        }
        when (mode) {
            T9Mode.Predictive -> predictive(key, buf, now)
            T9Mode.MultiTap -> multiTap(key, buf, now)
            T9Mode.Numeric -> numeric(key, buf)
        }
        return true
    }

    /** Commits candidate [index] (tapped in the action bar) followed by a space. */
    fun pick(index: Int, buf: T9Buffer) {
        if (!composing || index !in candidates.indices) return
        selected = index
        write(buf)
        commit(buf)
        insert(buf, " ")
    }

    fun setMode(next: T9Mode, buf: T9Buffer) {
        finish(buf)
        mode = next
    }

    /** Ends any composition or pending multi-tap letter, keeping the text. */
    fun finish(buf: T9Buffer) {
        commit(buf)
        finishTap(buf)
        punctPos = -1
    }

    /** Re-ranks the word being composed (e.g. once the dictionary has finished loading). */
    fun refresh(buf: T9Buffer) {
        dropIfMoved(buf)
        if (composing && digits.isNotEmpty()) recompute(buf)
    }

    /**
     * If the cursor was moved by touch since the last key, the composition no longer describes
     * the text around it: keep the text as it is and start fresh.
     */
    private fun dropIfMoved(buf: T9Buffer) {
        if (composing && (buf.selectionStart != buf.selectionEnd || buf.selectionEnd != composeStart + composeLen)) {
            clearComposition()
        }
        if (tapPos >= 0 && (buf.selectionStart != buf.selectionEnd || buf.selectionEnd != tapPos + 1)) {
            tapPos = -1
            tapKey = -1
            highlight = null
        }
    }

    /** Called periodically: settles a multi-tap letter once its key hasn't been pressed for a moment. */
    fun tick(buf: T9Buffer, now: Long) {
        if (tapPos >= 0 && now - tapAt >= TAP_MS) finishTap(buf)
    }

    val pendingTapDeadline: Long get() = if (tapPos >= 0) tapAt + TAP_MS else -1

    // ---- Predictive ----

    private fun predictive(key: T9Key, buf: T9Buffer, now: Long) {
        when (key) {
            T9Key.D2, T9Key.D3, T9Key.D4, T9Key.D5, T9Key.D6, T9Key.D7, T9Key.D8, T9Key.D9 -> {
                punctPos = -1
                if (!composing) startComposing(buf)
                digits.append(('0' + key.digit))
                recompute(buf)
            }
            T9Key.D1 -> {
                if (composing && hasMatches("$digits" + "1")) {
                    digits.append('1')
                    recompute(buf)
                } else {
                    commit(buf)
                    punctuation(buf, now)
                }
            }
            T9Key.D0 -> {
                commit(buf)
                punctPos = -1
                insert(buf, " ")
            }
            T9Key.Star -> if (composing && candidates.size > 1) {
                selected = (selected + 1) % candidates.size
                write(buf)
            } else if (!composing) {
                cycleCase()
            }
            T9Key.Delete -> if (composing) {
                digits.setLength(digits.length - 1)
                if (digits.isEmpty()) {
                    val start = composeStart
                    buf.replace(start, start + composeLen, "", start, start)
                    clearComposition()
                } else {
                    recompute(buf)
                }
            } else {
                punctPos = -1
                deleteBack(buf)
            }
            T9Key.Enter -> if (composing) commit(buf) else insert(buf, "\n")
            T9Key.Pound -> Unit
        }
    }

    private fun hasMatches(seq: String) = user.lookup(seq).isNotEmpty() || words().lookup(seq, 1).isNotEmpty()

    private fun startComposing(buf: T9Buffer) {
        // Typing over a selection replaces it.
        val s = buf.selectionStart
        val e = buf.selectionEnd
        if (s != e) buf.replace(s, e, "", s, s)
        composeStart = s
        composeLen = 0
        digits.setLength(0)
    }

    private fun recompute(buf: T9Buffer) {
        val learned = user.lookup(digits)
        val dict = words().lookup(digits, MAX_CANDIDATES)
        var merged = (learned + dict).distinct().take(MAX_CANDIDATES)
        unknown = merged.isEmpty()
        if (unknown) {
            // No word fits: extend the previous guess with the new key's first letter, so the user
            // sees something sensible and can switch to multi-tap to spell it.
            val prev = raw.getOrNull(selected).orEmpty()
            val last = digits.last()
            val letter = MULTI_TAP[last - '0'].first()
            merged = listOf(prev.take(digits.length - 1).padEnd(digits.length - 1, letter) + letter)
        }
        raw = merged
        selected = 0
        candidates = merged.map { applyCase(it, buf.text, composeStart) }
        write(buf)
    }

    private fun write(buf: T9Buffer) {
        val word = candidates[selected]
        val start = composeStart
        val end = start + word.length
        buf.replace(start, start + composeLen, word, end, end)
        composeLen = word.length
        highlight = TextRange(start, end)
    }

    private fun commit(buf: T9Buffer) {
        if (!composing) return
        if (!unknown) raw.getOrNull(selected)?.let(::learn)
        clearComposition()
    }

    private fun clearComposition() {
        composeStart = -1
        composeLen = 0
        digits.setLength(0)
        raw = emptyList()
        candidates = emptyList()
        selected = 0
        unknown = false
        highlight = null
    }

    private fun punctuation(buf: T9Buffer, now: Long) {
        val cursor = buf.selectionEnd
        if (punctPos >= 0 && punctPos == cursor - 1 && now - punctAt < TAP_MS) {
            punctIndex = (punctIndex + 1) % PUNCTUATION.length
            val c = PUNCTUATION[punctIndex].toString()
            buf.replace(punctPos, punctPos + 1, c, punctPos + 1, punctPos + 1)
        } else {
            // "hello ." reads as a typo; attach punctuation to the word it follows.
            var at = cursor
            val text = buf.text
            if (at >= 2 && text[at - 1] == ' ' && text[at - 2].isLetterOrDigit() && buf.selectionStart == cursor) {
                buf.replace(at - 1, at, "", at - 1, at - 1)
                at -= 1
            }
            punctIndex = 0
            insert(buf, PUNCTUATION[0].toString())
            punctPos = at
        }
        punctAt = now
    }

    // ---- Multi-tap ----

    private fun multiTap(key: T9Key, buf: T9Buffer, now: Long) {
        val d = key.digit
        when {
            d >= 0 -> {
                val letters = MULTI_TAP[d]
                if (tapPos >= 0 && tapKey == d && now - tapAt < TAP_MS) {
                    tapIndex = (tapIndex + 1) % letters.length
                    val c = caseChar(letters[tapIndex], buf.text, tapPos).toString()
                    buf.replace(tapPos, tapPos + 1, c, tapPos + 1, tapPos + 1)
                } else {
                    finishTap(buf)
                    if (d == 0 || d == 1) learnWordBefore(buf, buf.selectionStart)
                    val s = buf.selectionStart
                    val c = caseChar(letters[0], buf.text, s).toString()
                    buf.replace(s, buf.selectionEnd, c, s + 1, s + 1)
                    tapPos = s
                    tapIndex = 0
                    tapKey = d
                }
                tapAt = now
                highlight = TextRange(tapPos, tapPos + 1)
            }
            key == T9Key.Star -> { finishTap(buf); cycleCase() }
            key == T9Key.Delete -> { finishTap(buf); deleteBack(buf) }
            key == T9Key.Enter -> { finishTap(buf); learnWordBefore(buf, buf.selectionStart); insert(buf, "\n") }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun finishTap(buf: T9Buffer) {
        if (tapPos < 0) return
        tapPos = -1
        tapKey = -1
        highlight = null
    }

    private fun learnWordBefore(buf: T9Buffer, pos: Int) {
        val text = buf.text
        var i = pos
        while (i > 0 && (text[i - 1].isLetter() || text[i - 1] == '\'')) i--
        if (pos - i >= 2) learn(text.substring(i, pos))
    }

    // ---- Numeric ----

    private fun numeric(key: T9Key, buf: T9Buffer) {
        when {
            key.digit >= 0 -> insert(buf, key.digit.toString())
            key == T9Key.Star -> insert(buf, "*")
            key == T9Key.Delete -> deleteBack(buf)
            key == T9Key.Enter -> insert(buf, "\n")
        }
    }

    // ---- Shared ----

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

    private fun applyCase(word: String, text: CharSequence, at: Int): String = when (case) {
        T9Case.Upper -> word.uppercase()
        T9Case.Lower -> word
        T9Case.Sentence -> when {
            word == "i" || word.startsWith("i'") -> "I" + word.substring(1)
            sentenceStart(text, at) -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    private fun caseChar(c: Char, text: CharSequence, at: Int): Char = when (case) {
        T9Case.Upper -> c.uppercaseChar()
        T9Case.Lower -> c
        T9Case.Sentence -> if (sentenceStart(text, at)) c.uppercaseChar() else c
    }

    companion object {
        const val TAP_MS = 900L
        const val MAX_CANDIDATES = 6
        const val PUNCTUATION = ".,?!'-:;@/"
        val MULTI_TAP = arrayOf(" 0", ".,?!'-1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9")

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
