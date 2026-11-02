package hrln.interactive.sidekit.apps.compass

import org.junit.Assert.*
import org.junit.Test
class HeadingSensorTest {
    @Test fun `smoothing crosses north by shortest arc`() {
        assertEquals(0f, smoothHeading(359f, 1f, .5f), .001f)
        assertEquals(0f, smoothHeading(1f, 359f, .5f), .001f)
        assertEquals(45f, smoothHeading(0f, 90f, .5f), .001f)
    }
}
