package hrln.interactive.sidekit.design.input

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class StringBuffer(var value: String = "", var start: Int = value.length, var end: Int = value.length) : T9Buffer {
    override val text: CharSequence get() = value
    override val selectionStart get() = start
    override val selectionEnd get() = end
    override fun replace(start: Int, end: Int, text: String, selectStart: Int, selectEnd: Int) {
        value = value.substring(0, start) + text + value.substring(end)
        this.start = selectStart
        this.end = selectEnd
    }
}

class T9EngineTest {
    // Frequency order matters: "hello" is more common than "help" here.
    private val words = T9Words.parse("i\nthe\nhe\nhello\nhelp\nhell\nhelmet\ngood\nhome\ndont\nim\nthere\n")
    private var now = 0L
    private fun engine(user: T9UserWords = T9UserWords()) = T9Engine({ words }, user)

    /** Types keys: digits, `*`, `#`, `<` delete, `.` a pause long enough to settle a letter. */
    private fun T9Engine.type(buf: T9Buffer, keys: String) {
        for (c in keys) {
            if (c == '.') {
                now += 2_000
                tick(buf, now)
                continue
            }
            val key = when (c) {
                '*' -> T9Key.Star
                '#' -> T9Key.Pound
                '<' -> T9Key.Delete
                else -> T9Key.entries[c - '0']
            }
            now += 50
            key(key, buf, now)
        }
    }

    @Test fun `dictionary completes a prefix in frequency order`() {
        assertEquals(listOf("he", "hello", "help"), words.complete("he", 3))
        assertEquals(listOf("hello", "help", "hell", "helmet"), words.complete("hel", 6))
        assertEquals(emptyList<String>(), words.complete("zz", 3))
        assertEquals(3, words.rank("hello"))
        assertEquals(-1, words.rank("hel"))
    }

    @Test fun `repeated presses step through a key's letters`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "44")
        assertEquals("H", b.value) // sentence start
        assertEquals(TextRange(0, 1), e.highlight) // the letter still cycling
        e.type(b, ".444")
        assertEquals("Hi", b.value)
        e.type(b, ".")
        assertNull(e.highlight)
    }

    @Test fun `a different key settles the letter without waiting`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4433")
        assertEquals("He", b.value)
    }

    @Test fun `completions follow the letters typed`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4433.555")
        assertEquals("Hel", b.value)
        assertTrue(e.composing)
        assertEquals(listOf("Hello", "Help", "Hell"), e.candidates)
    }

    @Test fun `picking a completion replaces the partial word and adds a space`() {
        val user = T9UserWords()
        val e = engine(user)
        val b = StringBuffer()
        e.type(b, "4433.555")
        e.pick(1, b)
        assertEquals("Help ", b.value)
        assertFalse(e.composing)
        assertEquals(1, user.count("help"))
    }

    @Test fun `OK takes the highlighted completion and D-pad moves the highlight`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4433.555")
        assertTrue(e.moveSelection(1))
        assertEquals(1, e.selected)
        assertTrue(e.acceptSelected(b))
        assertEquals("Help ", b.value)
    }

    @Test fun `a typed word that is already a word comes first and OK keeps it`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4433")
        assertEquals("He", e.candidates.first())
        assertFalse(e.acceptSelected(b)) // nothing to complete: OK does its usual job
        assertEquals("He", b.value)
    }

    @Test fun `a rare fragment that is technically a word does not push out real completions`() {
        // "hel" sits far down the list, like the subtitle-derived dictionary's junk entries.
        val filler = (1..4000).joinToString("\n") { "x" + it.toString().map { d -> 'a' + (d - '0') }.joinToString("") }
        val big = T9Words.parse("hello\nhelp\n$filler\nhel\n")
        val e = T9Engine({ big })
        val b = StringBuffer()
        e.type(b, "4433.555")
        assertEquals(listOf("Hello", "Help", "Hel"), e.candidates) // still offered, just not first
    }

    @Test fun `words used often outrank the dictionary`() {
        val user = T9UserWords(mapOf("helmet" to 3))
        val e = engine(user)
        val b = StringBuffer()
        e.type(b, "4433.555")
        assertEquals("Helmet", e.candidates.first())
    }

    @Test fun `contractions are suggested with their apostrophe`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "3666.66")
        assertEquals("Don", b.value)
        assertEquals("Don't", e.candidates.first())
    }

    @Test fun `zero types a space and learns the word`() {
        val user = T9UserWords()
        val e = engine(user)
        val b = StringBuffer()
        e.type(b, "8.44.330")
        assertEquals("The ", b.value)
        assertFalse(e.composing)
        assertEquals(1, user.count("the"))
    }

    @Test fun `punctuation attaches to the word before a space`() {
        val e = engine()
        val b = StringBuffer("Hello ")
        e.type(b, "1")
        assertEquals("Hello.", b.value)
        e.type(b, "1")
        assertEquals("Hello,", b.value)
    }

    @Test fun `pound cycles case and long pound toggles numbers`() {
        val e = engine()
        val b = StringBuffer("so ")
        e.type(b, "#")
        assertEquals(T9Case.Upper, e.case)
        e.type(b, "44")
        assertEquals("so H", b.value)
        e.key(T9Key.Pound, b, now, long = true)
        assertEquals(T9Mode.Numeric, e.mode)
        e.type(b, "42*")
        assertEquals("so H42*", b.value)
    }

    @Test fun `long press types the digit`() {
        val e = engine()
        val b = StringBuffer()
        e.key(T9Key.D7, b, 0, long = true)
        assertEquals("7", b.value)
    }

    @Test fun `star cycles symbols`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "**")
        assertEquals("+", b.value)
    }

    @Test fun `delete removes the last character and updates completions`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4433.555<")
        assertEquals("He", b.value)
        assertEquals("He", e.candidates.first())
        e.type(b, "<<")
        assertEquals("", b.value)
        assertFalse(e.composing)
    }

    @Test fun `moving the cursor by touch starts fresh`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4433")
        b.start = 0; b.end = 0
        e.type(b, "8")
        assertEquals("THe", b.value)
    }

    @Test fun `custom key groups type by multi-tap too`() {
        val e = engine()
        val b = StringBuffer()
        e.tap(1, "qw", b, 0)
        e.tap(1, "qw", b, 100)
        assertEquals("W", b.value)
    }

    @Test fun `a key with one letter types it again on each press`() {
        val e = engine()
        val b = StringBuffer("he")
        e.tap(40, "l", b, 0)
        e.tap(40, "l", b, 100)
        assertEquals("hell", b.value)
    }
}
