package hrln.interactive.sidekit.apps.clock

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClockRepositoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private class MemoryStore(var saved: ClockData = ClockData()) : ClockStore {
        override fun read() = saved
        override fun write(data: ClockData) { saved = data }
    }
    private var now = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
    private var elapsed = 1000L
    private lateinit var store: MemoryStore
    private lateinit var repo: ClockRepository
    @Before fun setup() {
        store = MemoryStore()
        repo = ClockRepository(store, scope.backgroundScope, dispatcher, now = { now }, elapsed = { elapsed })
        drain()
    }
    private fun drain() = scope.testScheduler.runCurrent()
    @Test fun `one shot fires once and snooze survives reopening`() {
        repo.save(ClockAlarm(id = "one", hour = 12)); drain()
        val at = store.saved.alarms.single().nextAt
        assertNull(repo.fire("one", at))
        now = at
        assertNotNull(repo.fire("one", at)); drain()
        assertFalse(store.saved.alarms.single().enabled)
        assertNull(repo.fire("one", at))
        repo.snooze(); drain()
        assertEquals(now + 300000, store.saved.alarms.single().nextAt)
        assertTrue(store.saved.alarms.single().enabled)
        val reopened = ClockRepository(store, scope.backgroundScope, dispatcher, now = { now }, elapsed = { elapsed })
        drain()
        assertEquals(repo.data.value, reopened.data.value)
    }
    @Test fun `repeat schedules next selected day and dismiss preserves future alarm`() {
        repo.save(ClockAlarm(id = "repeat", hour = 12, days = setOf(1, 3))); drain()
        now = store.saved.alarms.single().nextAt
        repo.fire("repeat", now); repo.dismiss(); drain()
        assertTrue(store.saved.alarms.single().enabled)
        assertTrue(store.saved.alarms.single().nextAt > now)
        assertNull(store.saved.ringing)
    }
    @Test fun `timer pause resume cancel and stale deliveries`() {
        repo.startTimer(60000); drain()
        val original = store.saved.timer.endAt!!
        now += 20000; elapsed += 20000
        repo.pauseTimer(); drain()
        assertEquals(40000L, store.saved.timer.remaining)
        repo.startTimer(store.saved.timer.remaining); drain()
        now += 40000; elapsed += 40000
        assertNotNull(repo.fire(ClockRepository.TIMER_ID, original))
        repo.dismiss(); repo.startTimer(10000); repo.cancelTimer(); drain()
        now += 10000; elapsed += 10000
        assertNull(repo.fire(ClockRepository.TIMER_ID, now))
        assertNull(store.saved.timer.endAt)
    }
    @Test fun `timer is unaffected by wall clock changes`() {
        repo.startTimer(60000); drain()
        val end = store.saved.timer.endAt!!
        now -= 3600000
        elapsed += 60000
        assertNotNull(repo.fire(ClockRepository.TIMER_ID, end))
    }
    @Test fun `stopwatch uses monotonic timestamps and laps survive pause`() {
        repo.toggleStopwatch()
        elapsed += 12000; now -= 3600000
        repo.lap(); repo.toggleStopwatch(); drain()
        assertEquals(12000L, store.saved.stopwatch.elapsed)
        assertEquals(listOf(12000L), store.saved.stopwatch.laps)
        elapsed += 5000; repo.toggleStopwatch(); elapsed += 3000
        repo.toggleStopwatch(); drain()
        assertEquals(15000L, store.saved.stopwatch.elapsed)
        repo.resetStopwatch(); drain()
        assertEquals(Stopwatch(), store.saved.stopwatch)
    }
    @Test fun `reboot does not interpret an old monotonic start as current uptime`() {
        store.saved = ClockData(stopwatch = Stopwatch(2000, 3000, listOf(1000)), boot = 1)
        val rebooted = ClockRepository(store, scope.backgroundScope, dispatcher, now = { now }, elapsed = { 100 }, boot = 2)
        drain()
        assertNull(rebooted.data.value!!.stopwatch.startedAt)
        assertEquals(3000L, rebooted.data.value!!.stopwatch.elapsed)
    }
    @Test fun `repeat date handles DST and rolls to next week`() {
        val zone = ZoneId.of("Australia/Sydney")
        val before = ZonedDateTime.of(2026, 10, 4, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val next = nextAlarm(2, 30, setOf(7), before, zone)
        assertEquals(3, Instant.ofEpochMilli(next).atZone(zone).hour)
        assertTrue(nextAlarm(2, 30, setOf(7), next, zone) > next)
    }
    @Test fun `atomic store round trip preserves all state`() {
        val file = Files.createTempDirectory("clock-test").resolve("clock.json").toFile()
        try {
            val disk = FileClockStore(file)
            val data = ClockData(listOf(ClockAlarm(id = "alarm", days = setOf(1, 7), nextAt = now)), ClockTimer(now + 1000), Stopwatch(1, 2, listOf(3)), 2, Ringing("alarm", "Alarm", now))
            disk.write(data)
            assertEquals(data, disk.read())
        } finally { file.parentFile!!.deleteRecursively() }
    }
    @Test fun `disabled and deleted alarms are persisted`() {
        repo.save(ClockAlarm(id = "one")); repo.toggle("one"); drain()
        assertFalse(store.saved.alarms.single().enabled)
        repo.delete("one"); drain()
        assertTrue(store.saved.alarms.isEmpty())
    }

    @Test fun `after the undo window a deleted alarm is really removed`() {
        store.saved = ClockData(alarms = listOf(ClockAlarm(id = "a", hour = 7, minute = 30)))
        repo = ClockRepository(store, scope.backgroundScope, dispatcher, now = { now }, elapsed = { elapsed })
        drain()
        repo.deleteWithUndo("a"); drain()
        // Hidden from the list at once, but the store is only touched when the window closes.
        assertTrue(repo.pendingIds.value.contains("a"))
        assertNotNull(repo.deleted.value)
        assertTrue(store.saved.alarms.any { it.id == "a" })
        scope.testScheduler.advanceTimeBy(ClockRepository.UNDO_WINDOW_MS + 1); drain()
        assertTrue(store.saved.alarms.none { it.id == "a" })
        assertFalse(repo.pendingIds.value.contains("a"))
        assertNull(repo.deleted.value)
    }

    @Test fun `undo restores an alarm before the window closes`() {
        store.saved = ClockData(alarms = listOf(ClockAlarm(id = "a", hour = 7, minute = 30, days = setOf(1, 5), enabled = true)))
        repo = ClockRepository(store, scope.backgroundScope, dispatcher, now = { now }, elapsed = { elapsed })
        drain()
        repo.deleteWithUndo("a"); drain()
        repo.undoDelete(); drain()
        val restored = store.saved.alarms.single { it.id == "a" }
        assertTrue(restored.enabled)
        assertEquals(setOf(1, 5), restored.days)
        assertEquals(30, restored.minute)
        assertFalse(repo.pendingIds.value.contains("a"))
        assertNull(repo.deleted.value)
        scope.testScheduler.advanceTimeBy(ClockRepository.UNDO_WINDOW_MS + 1); drain()
        assertTrue(store.saved.alarms.any { it.id == "a" })
    }

    @Test fun `a new delete commits the one still on offer first`() {
        store.saved = ClockData(alarms = listOf(ClockAlarm(id = "a", hour = 7, minute = 30), ClockAlarm(id = "b", hour = 8, minute = 0)))
        repo = ClockRepository(store, scope.backgroundScope, dispatcher, now = { now }, elapsed = { elapsed })
        drain()
        repo.deleteWithUndo("a"); drain()
        repo.deleteWithUndo("b"); drain()
        // The earlier delete is committed, the newest waits out its own window.
        assertTrue(store.saved.alarms.none { it.id == "a" })
        assertTrue(store.saved.alarms.any { it.id == "b" })
        assertTrue(repo.pendingIds.value.contains("b"))
        assertFalse(repo.pendingIds.value.contains("a"))
        scope.testScheduler.advanceTimeBy(ClockRepository.UNDO_WINDOW_MS + 1); drain()
        assertTrue(store.saved.alarms.none { it.id == "b" })
    }

    @Test fun `deleting an unknown id changes nothing and offers no undo`() {
        repo.deleteWithUndo("missing"); drain()
        assertTrue(repo.pendingIds.value.isEmpty())
        assertNull(repo.deleted.value)
    }
}
