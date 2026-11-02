package hrln.interactive.sidekit.apps.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarViewModelTest {
    @Test fun `starts on Monday 2 with its two events`() {
        val state = CalendarViewModel().state.value
        assertEquals(CalendarDay("M", 2), state.selectedDay)
        assertEquals(listOf("9:30", "12:00"), state.events.map { it.time })
    }

    @Test fun `selecting a day without events shows none`() {
        val vm = CalendarViewModel()
        vm.selectDay(4)
        assertEquals(CalendarDay("F", 6), vm.state.value.selectedDay)
        assertTrue(vm.state.value.events.isEmpty())
    }

    @Test fun `out of range selections are ignored`() {
        val vm = CalendarViewModel()
        vm.selectDay(2)
        vm.selectDay(7)
        vm.selectDay(-1)
        assertEquals(2, vm.state.value.selectedIndex)
    }
}
