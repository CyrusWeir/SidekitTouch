package hrln.interactive.sidekit.apps.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslateUiStateTest {
    private val base = TranslateUiState()

    @Test fun startsEmptyWithoutPretendingToTranslate() {
        assertEquals("English", base.source.language)
        assertEquals("", base.source.text)
        assertEquals("Chinese", base.target.language)
        assertEquals("", base.target.text)
        assertNull(base.talking)
    }

    @Test fun micTogglesWithoutInsertingSampleText() {
        val listening = base.toggleMic(TranslateSide.Source)
        assertEquals(TranslateSide.Source, listening.talking)
        assertEquals("", listening.source.text)
        assertNull(listening.toggleMic(TranslateSide.Source).talking)
        assertEquals(TranslateSide.Target, listening.toggleMic(TranslateSide.Target).talking)
    }
}
