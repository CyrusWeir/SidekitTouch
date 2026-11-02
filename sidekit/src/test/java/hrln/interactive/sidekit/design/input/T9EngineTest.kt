package hrln.interactive.sidekit.design.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    // Frequency order matters: "good" is more common than "home" here.
    private val words = T9Words.parse("i\nthe\ngood\nhome\ngone\nhood\nhello\nhe\nin\ni'm\nim\nof\nme\n")
    private var now = 0L
    private fun engine(user: T9UserWords = T9UserWords()) = T9Engine({ words }, user)
    private fun T9Engine.type(buf: T9Buffer, keys: String) {
        for (c in keys) {
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

    @Test fun `dictionary lookup ranks exact matches by frequency then completions`() {
        assertEquals(listOf("good", "home", "gone", "hood"), words.lookup("4663", 6))
        assertEquals(listOf("he", "hello"), words.lookup("43", 6).take(2))
        assertEquals(listOf("i'm"), words.lookup("416", 6))
        assertEquals(emptyList<String>(), words.lookup("999", 6))
    }

    @Test fun `typing composes the best word inline and highlights it`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4663")
        assertEquals("Good", b.value) // sentence start
        assertEquals(4, b.start) // cursor stays collapsed after the word...
        assertEquals(androidx.compose.ui.text.TextRange(0, 4), e.highlight) // ...which is highlighted
        assertTrue(e.composing)
        assertEquals(listOf("Good", "Home", "Gone", "Hood"), e.candidates)
    }

    @Test fun `star cycles alternatives and zero commits with a space`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4663*0")
        assertEquals("Home ", b.value)
        assertFalse(e.composing)
        e.type(b, "4663")
        // Mid-sentence, so lowercase; and "home", just chosen, is now learned and ranked first.
        assertEquals("Home home", b.value)
    }

    @Test fun `picking a suggestion commits it and learns it above the dictionary`() {
        val user = T9UserWords()
        val e = engine(user)
        val b = StringBuffer()
        e.type(b, "4663")
        e.pick(3, b)
        assertEquals("Hood ", b.value)
        e.type(b, "4663")
        assertEquals("hood", e.candidates.first())
    }

    @Test fun `delete removes keys while composing then characters`() {
        val e = engine()
        val b = StringBuffer("ok ")
        e.type(b, "43")
        assertEquals("ok he", b.value)
        e.type(b, "<")
        assertEquals("ok I", b.value) // "4" alone is the word "i"
        e.type(b, "<")
        assertEquals("ok ", b.value)
        assertFalse(e.composing)
        e.type(b, "<")
        assertEquals("ok", b.value)
    }

    @Test fun `one gives punctuation attached to the previous word, repeated presses cycle it`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "430")
        e.type(b, "1")
        assertEquals("He.", b.value)
        e.type(b, "1")
        assertEquals("He,", b.value)
    }

    @Test fun `apostrophes are typed on one inside a word`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "416")
        assertEquals("I'm", b.value)
    }

    @Test fun `pound cycles to multi-tap where repeated presses step through letters`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "#")
        assertEquals(T9Mode.MultiTap, e.mode)
        e.type(b, "44")
        assertEquals("H", b.value)
        now += 2_000
        e.tick(b, now)
        e.type(b, "444")
        assertEquals("Hi", b.value)
        assertEquals(androidx.compose.ui.text.TextRange(1, 2), e.highlight) // pending letter
        now += 2_000
        e.tick(b, now)
        assertEquals(null, e.highlight)
    }

    @Test fun `numeric mode types digits and long press types a digit in any mode`() {
        val e = engine()
        val b = StringBuffer()
        e.key(T9Key.D7, b, 0, long = true)
        assertEquals("7", b.value)
        e.type(b, "##")
        assertEquals(T9Mode.Numeric, e.mode)
        e.type(b, "42*")
        assertEquals("742*", b.value)
    }

    @Test fun `unknown sequences fall back to letters and are not learned`() {
        val user = T9UserWords()
        val e = engine(user)
        val b = StringBuffer()
        e.type(b, "999")
        assertTrue(e.unknown)
        assertEquals(3, b.value.length)
        e.type(b, "0")
        assertEquals(0, user.count(b.value.trim()))
    }

    @Test fun `moving the cursor by touch ends the composition without changing text`() {
        val e = engine()
        val b = StringBuffer()
        e.type(b, "4663")
        b.start = 0; b.end = 0
        e.type(b, "43")
        assertEquals("HeGood", b.value)
    }

    @Test fun `star changes case when idle`() {
        val e = engine()
        val b = StringBuffer("so ")
        e.type(b, "*")
        assertEquals(T9Case.Upper, e.case)
        e.type(b, "43")
        assertEquals("so HE", b.value)
    }
}
