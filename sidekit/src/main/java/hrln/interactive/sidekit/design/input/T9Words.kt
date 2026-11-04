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
 * The completion dictionary: words in frequency order, indexed alphabetically for prefix search.
 *
 * Memory layout is chosen for a 4 GB phone: all words live in one [String] (ASCII, so ART stores
 * it at one byte per char, ~320 KB for 40k words) with an [IntArray] of start offsets, and the
 * prefix index is one more [IntArray] of word numbers in alphabetical order. About 0.65 MB in
 * total, with no per-word objects.
 */
class T9Words private constructor(
    private val blob: String,
    private val starts: IntArray,
    private val sorted: IntArray,
) {
    val size: Int get() = starts.size - 1

    /** Word with frequency rank [rank] (0 = most frequent). */
    fun word(rank: Int): String = blob.substring(starts[rank], starts[rank + 1] - 1)

    private fun length(rank: Int) = starts[rank + 1] - 1 - starts[rank]

    /** Compares the first `prefix.length` letters of a word with [prefix] (a word shorter than it sorts first). */
    private fun comparePrefix(rank: Int, prefix: String): Int {
        val start = starts[rank]
        val len = length(rank)
        val n = minOf(len, prefix.length)
        for (i in 0 until n) {
            val c = blob[start + i]
            if (c != prefix[i]) return c - prefix[i]
        }
        return if (len < prefix.length) -1 else 0
    }

    /**
     * The [limit] most frequent words starting with [prefix] (lowercase letters), the word itself
     * included when it is one. Results are in frequency order.
     */
    fun complete(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || size == 0 || limit <= 0) return emptyList()
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparePrefix(sorted[mid], prefix) < 0) lo = mid + 1 else hi = mid
        }
        // Keep the best-ranked few. Every word with the prefix is contiguous from `lo`; even a
        // single letter is at most a few thousand words, so a full scan of the run is cheap.
        val best = IntArray(limit) { Int.MAX_VALUE }
        var i = lo
        while (i < sorted.size && comparePrefix(sorted[i], prefix) == 0) {
            val rank = sorted[i]
            if (rank < best[limit - 1]) {
                var j = limit - 1
                while (j > 0 && best[j - 1] > rank) { best[j] = best[j - 1]; j-- }
                best[j] = rank
            }
            i++
        }
        return best.filter { it != Int.MAX_VALUE }.map(::word)
    }

    /** Frequency rank of [word] (0 = most frequent), or -1 if it isn't in the dictionary. */
    fun rank(word: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val c = comparePrefix(sorted[mid], word)
            if (c < 0) lo = mid + 1 else hi = mid
        }
        while (lo < sorted.size && comparePrefix(sorted[lo], word) == 0) {
            if (length(sorted[lo]) == word.length) return sorted[lo]
            lo++
        }
        return -1
    }

    companion object {
        val Empty = T9Words("", intArrayOf(0), IntArray(0))

        /** Builds the index from newline-separated lowercase words in frequency order. */
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
            val order = (0 until count).sortedWith(compareBy<Int>({ words.word(it) }, { it })).toIntArray()
            return T9Words(blob, starts, order)
        }
    }
}

/**
 * Words the user has typed, with how often. Ranked above the dictionary, and the only way words
 * outside it (names, slang) get suggested.
 */
class T9UserWords(initial: Map<String, Int> = emptyMap()) {
    private val counts = HashMap(initial)
    var version = 0
        private set

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.any { !it.isLetter() && it != '\'' }) return
        counts[w] = (counts[w] ?: 0) + 1
        version++
    }

    fun forget(word: String) {
        if (counts.remove(word.lowercase()) != null) version++
    }

    fun snapshot(): Map<String, Int> = HashMap(counts)

    /** Learned words starting with [prefix] (lowercase, apostrophes ignored), most used first. */
    fun complete(prefix: String): List<String> {
        if (counts.isEmpty() || prefix.isEmpty()) return emptyList()
        return counts.entries.filter { it.key.replace("'", "").startsWith(prefix) }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /** How many times [word] has been typed. */
    fun count(word: String): Int = counts[word.lowercase()] ?: 0
}
