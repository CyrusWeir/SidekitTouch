package hrln.interactive.sidekit.apps.sideport

import hrln.interactive.sidekit.apps.sideport.interpret.Block
import hrln.interactive.sidekit.apps.sideport.interpret.ListStitcher
import hrln.interactive.sidekit.apps.sideport.interpret.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class ListStitcherTest {
    private fun rows(vararg keys: String): List<Block> = keys.map { TextBlock(it, it) }
    private fun List<Block>.keys() = map { it.key }

    @Test fun `scrolling forward appends new rows and tracks the visible window`() {
        val s = ListStitcher()
        s.merge("screen", rows("a", "b", "c", "d"))
        val merged = s.merge("screen", rows("c", "d", "e", "f"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), merged.keys())
        assertEquals(2..5, s.visible)
    }

    @Test fun `scrolling back prepends rows`() {
        val s = ListStitcher()
        s.merge("screen", rows("e", "f", "g"))
        val merged = s.merge("screen", rows("c", "d", "e"))
        assertEquals(listOf("c", "d", "e", "f", "g"), merged.keys())
        assertEquals(0..2, s.visible)
    }

    @Test fun `a window inside the stitched list replaces that stretch`() {
        val s = ListStitcher()
        s.merge("screen", rows("a", "b", "c", "d", "e"))
        s.merge("screen", rows("d", "e", "f"))
        // Row "b" changed (e.g. a new unread count) while we scrolled back over it.
        val merged = s.merge("screen", rows("a", "b2", "c"))
        assertEquals(listOf("a", "b2", "c", "d", "e", "f"), merged.keys())
    }

    @Test fun `no overlap or a new screen starts over`() {
        val s = ListStitcher()
        s.merge("screen", rows("a", "b"))
        assertEquals(listOf("x", "y"), s.merge("screen", rows("x", "y")).keys())
        assertEquals(listOf("x"), s.merge("other", rows("x")).keys())
    }

    @Test fun `rows that moved into the window aren't repeated`() {
        val (merged, _) = ListStitcher.mergeWindow(rows("a", "b", "c", "d"), rows("b", "new", "d", "c"))
        assertEquals(listOf("a", "b", "new", "d", "c"), merged.keys())
    }
}
