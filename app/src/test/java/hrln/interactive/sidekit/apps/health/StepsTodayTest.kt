package hrln.interactive.sidekit.apps.health

import org.junit.Assert.assertEquals
import org.junit.Test

class StepsTodayTest {
    @Test fun `mock values format and fill 321 of 400`() {
        val steps = StepsToday(6420, 8000)
        assertEquals("6,420", steps.stepsText)
        assertEquals("/ 8,000", steps.goalText)
        assertEquals(321f, steps.progress * 400, 0.5f)
    }

    @Test fun `progress is clamped`() {
        assertEquals(1f, StepsToday(9000, 8000).progress, 0f)
        assertEquals(0f, StepsToday(100, 0).progress, 0f)
        assertEquals(0f, StepsToday(-5, 100).progress, 0f)
    }
}
