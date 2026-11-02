package hrln.interactive.sidekit.apps.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslateUiStateTest {
    private val base = TranslateUiState()

    @Test fun startsWithBaseMockText() {
        assertEquals("English", base.source.language)
        assertEquals("Apple", base.source.text)
        assertEquals("Chinese", base.target.language)
        assertEquals("苹果", base.target.text)
        assertNull(base.talking)
    }

    @Test fun tappingMicOpensThatSide() {
        assertEquals(TranslateSide.Target, base.toggleMic(TranslateSide.Target).talking)
        assertEquals(TranslateSide.Source, base.toggleMic(TranslateSide.Source).talking)
    }

    @Test fun tappingOtherMicSwitchesSides() {
        val state = base.toggleMic(TranslateSide.Source).toggleMic(TranslateSide.Target)
        assertEquals(TranslateSide.Target, state.talking)
        assertEquals("Apple", state.source.text)
    }

    @Test fun tappingOpenMicStopsAndShowsHeardPhrase() {
        val state = base.toggleMic(TranslateSide.Target).toggleMic(TranslateSide.Target)
        assertNull(state.talking)
        assertEquals(TranslateSampleData.heard.first, state.source.text)
        assertEquals(TranslateSampleData.heard.second, state.target.text)
        assertEquals("English", state.source.language)
        assertEquals("Chinese", state.target.language)
    }
}
