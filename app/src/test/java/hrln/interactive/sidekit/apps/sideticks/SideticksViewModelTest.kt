package hrln.interactive.sidekit.apps.sideticks

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class SideticksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private class MemoryStore(var values: List<Tick> = emptyList()) : TickStore {
        var writes = 0
        override fun read() = values
        override fun write(ticks: List<Tick>) { values = ticks; writes++ }
    }
    private lateinit var store: MemoryStore
    private lateinit var repo: TicksRepository
    private lateinit var vm: SideticksViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        store = MemoryStore(listOf(Tick(1, "Milk", dueAt = 2000), Tick(2, "Bread", true)))
        repo = TicksRepository(store, scope.backgroundScope, dispatcher)
        vm = SideticksViewModel(repo)
        scope.backgroundScope.launch { vm.state.collect {} }
        scope.testScheduler.runCurrent()
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun drain() = scope.testScheduler.runCurrent()
    @Test fun `ticking persists and undo restores deleted reminders including due time`() {
        vm.toggle(1); drain()
        assertTrue(store.values.first().ticked)
        val tick = repo.ticks.value!!.first()
        vm.delete(listOf(tick)); drain()
        assertEquals(listOf(2), store.values.map { it.id })
        vm.undoDelete(); drain()
        assertEquals(tick, store.values.first { it.id == 1 })
        assertTrue(vm.state.value.recentlyDeleted.isEmpty())
    }
    @Test fun `clear completed is undoable and expires after five seconds`() {
        vm.clearTicked(); drain()
        assertEquals(listOf(1), store.values.map { it.id })
        assertEquals(1, vm.state.value.recentlyDeleted.size)
        scope.testScheduler.advanceTimeBy(5001); drain()
        assertTrue(vm.state.value.recentlyDeleted.isEmpty())
    }
    @Test fun `draft validation and due ordering`() {
        vm.startAdding(); vm.updateDraft("Call mum"); vm.setDue("invalid"); vm.confirmDraft(); drain()
        assertNotNull(vm.state.value.error)
        assertEquals(2, store.values.size)
        vm.setDue("2099-02-30 10:00"); vm.confirmDraft(); drain()
        assertNotNull(vm.state.value.error)
        vm.setDue("2099-01-01 10:00"); vm.confirmDraft(); drain()
        assertEquals("Call mum", store.values.first().title)
        vm.sort(); drain()
        assertEquals("Milk", vm.state.value.open.first().title)
        assertNull(vm.state.value.draft)
    }
    @Test fun `rapid edits conflate writes and cold load does not overwrite them`() {
        val before = store.writes
        repeat(10) { repo.toggle(1) }
        repo.add("Eggs", null)
        drain()
        assertEquals(before + 1, store.writes)
        val reopened = TicksRepository(store, scope.backgroundScope, dispatcher)
        drain()
        assertEquals(repo.ticks.value, reopened.ticks.value)
    }
    @Test fun `cannot mutate before file has loaded`() {
        val cold = TicksRepository(store, scope.backgroundScope, dispatcher)
        assertFalse(cold.add("Too early", null))
        drain()
        assertEquals(store.values, cold.ticks.value)
    }
    @Test fun `file round trip preserves due and notification status`() {
        val file = Files.createTempDirectory("ticks-test").resolve("ticks.bin").toFile()
        try {
            val disk = FileTickStore(file)
            val ticks = listOf(Tick(4, "Test 🥕", dueAt = 5000, notified = true))
            disk.write(ticks)
            assertEquals(ticks, disk.read())
            disk.write(emptyList())
            assertTrue(disk.read().isEmpty())
        } finally { file.parentFile!!.deleteRecursively() }
    }
    @Test fun `scheduling follows saved state including cancellation`() {
        val snapshots = mutableListOf<List<Tick>>()
        val scheduled = TicksRepository(store, scope.backgroundScope, dispatcher, TickScheduling { _, next -> snapshots += next })
        drain()
        scheduled.delete(setOf(1)); drain()
        assertEquals(store.values, snapshots.last())
        assertTrue(snapshots.last().none { it.id == 1 })
    }
}
