package hrln.interactive.sidekit.design.input

/** Keypad digit for a letter (`a`..`z`, any case), `'1'` for an apostrophe, or `0.toChar()` if none. */
fun t9Digit(c: Char): Char = when (c.lowercaseChar()) {
    'a', 'b', 'c' -> '2'
    'd', 'e', 'f' -> '3'
    'g', 'h', 'i' -> '4'
    'j', 'k', 'l' -> '5'
    'm', 'n', 'o' -> '6'
    'p', 'q', 'r', 's' -> '7'
    't', 'u', 'v' -> '8'
    'w', 'x', 'y', 'z' -> '9'
    '\'' -> '1'
    else -> 0.toChar()
}

/** Digit sequence for [word], or null if it has characters T9 can't type predictively. */
fun t9Key(word: String): String? {
    val sb = StringBuilder(word.length)
    for (c in word) {
        val d = t9Digit(c)
        if (d == 0.toChar()) return null
        sb.append(d)
    }
    return sb.toString()
}

/**
 * The prediction dictionary: words in frequency order, indexed by keypad sequence.
 *
 * Memory layout is chosen for a 4 GB phone: all words live in one [String] (ASCII, so ART stores
 * it at one byte per char, ~320 KB for 40k words) with an [IntArray] of start offsets, and the
 * key index is one more [IntArray] of word numbers sorted by digit sequence then frequency.
 * About 0.65 MB in total, no per-word objects, and digit keys are computed on the fly during
 * binary search rather than stored.
 */
class T9Words private constructor(
    private val blob: String,
    private val starts: IntArray,
    private val byKey: IntArray,
) {
    val size: Int get() = starts.size - 1

    /** Word with frequency rank [rank] (0 = most frequent). */
    fun word(rank: Int): String = blob.substring(starts[rank], starts[rank + 1] - 1)

    private fun length(rank: Int) = starts[rank + 1] - 1 - starts[rank]

    /**
     * Compares a word's digit sequence with [digits]: 0 when equal, negative when it sorts before,
     * positive after. A word whose sequence *starts with* [digits] but is longer sorts after.
     */
    private fun compare(rank: Int, digits: CharSequence): Int {
        val start = starts[rank]
        val len = length(rank)
        val n = minOf(len, digits.length)
        for (i in 0 until n) {
            val d = t9Digit(blob[start + i])
            if (d != digits[i]) return d - digits[i]
        }
        return len - digits.length
    }

    private fun hasPrefix(rank: Int, digits: CharSequence): Boolean {
        if (length(rank) < digits.length) return false
        val start = starts[rank]
        for (i in digits.indices) if (t9Digit(blob[start + i]) != digits[i]) return false
        return true
    }

    /**
     * Words for [digits]: exact-length matches most frequent first, then (if fewer than [limit])
     * the most frequent longer words that start with the sequence.
     */
    fun lookup(digits: CharSequence, limit: Int): List<String> {
        if (digits.isEmpty() || size == 0) return emptyList()
        var lo = 0
        var hi = byKey.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(byKey[mid], digits) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<String>(limit)
        var i = lo
        while (i < byKey.size && out.size < limit && compare(byKey[i], digits) == 0) {
            out.add(word(byKey[i]))
            i++
        }
        if (out.size < limit) {
            // Completions: keep the best-ranked few. The scan is capped so a one-digit prefix (which
            // matches thousands of words) costs a bounded amount of work per key press.
            val want = limit - out.size
            val best = IntArray(want) { Int.MAX_VALUE }
            var scanned = 0
            while (i < byKey.size && scanned < COMPLETION_SCAN && hasPrefix(byKey[i], digits)) {
                val rank = byKey[i]
                if (rank < best[want - 1]) {
                    var j = want - 1
                    while (j > 0 && best[j - 1] > rank) { best[j] = best[j - 1]; j-- }
                    best[j] = rank
                }
                i++
                scanned++
            }
            for (rank in best) if (rank != Int.MAX_VALUE) out.add(word(rank))
        }
        return out
    }

    companion object {
        private const val COMPLETION_SCAN = 4000

        val Empty = T9Words("", intArrayOf(0), IntArray(0))

        /** Builds the index from newline-separated words in frequency order. */
        fun parse(text: String): T9Words {
            val blob = if (text.endsWith('\n')) text else "$text\n"
            var count = 0
            for (c in blob) if (c == '\n') count++
            val starts = IntArray(count + 1)
            var w = 0
            var lineStart = 0
            for (i in blob.indices) {
                if (blob[i] == '\n') {
                    starts[w++] = lineStart
                    lineStart = i + 1
                }
            }
            starts[count] = blob.length
            val words = T9Words(blob, starts, IntArray(0))
            // Sort ranks by (digit sequence, rank). Keys are computed once here for the sort only.
            val keys = Array(count) { r -> t9Key(words.word(r)) ?: "" }
            val order = (0 until count).sortedWith(compareBy<Int>({ keys[it] }, { it })).toIntArray()
            return T9Words(blob, starts, order)
        }
    }
}

/**
 * Words the user has typed, with how often. Ranked above the dictionary for the same keys, and
 * the only way words outside the dictionary (names, slang) become predictable.
 */
class T9UserWords(initial: Map<String, Int> = emptyMap()) {
    private val counts = HashMap(initial)
    var version = 0
        private set

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || t9Key(w) == null) return
        counts[w] = (counts[w] ?: 0) + 1
        version++
    }

    fun forget(word: String) {
        if (counts.remove(word.lowercase()) != null) version++
    }

    fun snapshot(): Map<String, Int> = HashMap(counts)

    /** Exact matches for [digits], most used first. The map is small, so a scan is fine. */
    fun lookup(digits: CharSequence): List<String> {
        if (counts.isEmpty()) return emptyList()
        val d = digits.toString()
        return counts.entries.filter { it.key.length == d.length && t9Key(it.key) == d }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /** Whether [word] is known well enough to rank above the dictionary's own order. */
    fun count(word: String): Int = counts[word.lowercase()] ?: 0
}
