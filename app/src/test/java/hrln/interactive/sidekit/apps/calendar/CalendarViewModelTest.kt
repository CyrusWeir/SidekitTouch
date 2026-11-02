package hrln.interactive.sidekit.apps.calendar

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private class Fake : CalendarRepository {
        var from: LocalDate? = null
        var until: LocalDate? = null
        var denied = false
        var slow = false
        override suspend fun events(from: LocalDate, until: LocalDate): List<CalendarEvent> {
            if (slow) delay(1000)
            if (denied) throw SecurityException()
            this.from = from; this.until = until
            return listOf(CalendarEvent(1, "Meeting", 0, 3600000, 1))
        }
        override suspend fun calendars() = emptyList<DeviceCalendar>()
        override suspend fun event(id: Long): EventDraft? = null
        override suspend fun save(draft: EventDraft) {}
        override suspend fun delete(id: Long) {}
    }
    private lateinit var repo: Fake
    private lateinit var vm: CalendarViewModel
    @Before fun setup() { Dispatchers.setMain(dispatcher); repo = Fake(); vm = CalendarViewModel(repo, LocalDate.of(2026, 11, 2)) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun drain() = scope.testScheduler.runCurrent()
    @Test fun `day range comes from selected date not sample events`() {
        assertTrue(vm.state.value.events.isEmpty())
        vm.refresh(); drain()
        assertEquals(LocalDate.of(2026, 11, 2), repo.from)
        assertEquals(LocalDate.of(2026, 11, 3), repo.until)
        assertEquals("Meeting", vm.state.value.events.single().title)
    }
    @Test fun `month and agenda query their exact ranges`() {
        vm.mode(CalendarMode.Month); drain()
        assertEquals(LocalDate.of(2026, 11, 1), repo.from)
        assertEquals(LocalDate.of(2026, 12, 1), repo.until)
        vm.mode(CalendarMode.Agenda); drain()
        assertEquals(LocalDate.of(2026, 11, 9), repo.until)
    }
    @Test fun `week selection crosses month boundaries and ignores bad indices`() {
        vm.selectDate(LocalDate.of(2026, 11, 1)); vm.selectDay(0); drain()
        assertEquals(LocalDate.of(2026, 10, 26), vm.state.value.date)
        vm.selectDay(7); vm.selectDay(-1)
        assertEquals(0, vm.state.value.selectedIndex)
    }
    @Test fun `permission loss removes stale events and reports an actionable error`() {
        vm.refresh(); drain(); repo.denied = true; vm.refresh(); drain()
        assertTrue(vm.state.value.events.isEmpty())
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }
    @Test fun `a slow previous query cannot replace a newer date`() {
        repo.slow = true
        vm.refresh(); drain()
        vm.selectDay(3); drain()
        scope.testScheduler.advanceTimeBy(1001); drain()
        assertEquals(LocalDate.of(2026, 11, 5), repo.from)
        vm.stop()
    }
}
