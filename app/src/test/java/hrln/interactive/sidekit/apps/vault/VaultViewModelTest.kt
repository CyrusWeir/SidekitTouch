package hrln.interactive.sidekit.apps.vault

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = object : VaultStore {
        var notes = listOf(PrivateNote("1", "Private"))
        var fail = false
        override fun read() = notes
        override fun write(notes: List<PrivateNote>) { check(!fail); this.notes = notes }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun `save persists and lock removes notes and draft`() {
        val vm = VaultViewModel(store, dispatcher)
        vm.authenticated(); dispatcher.scheduler.runCurrent()
        vm.edit(); vm.text("New private note"); vm.save(); dispatcher.scheduler.runCurrent()
        assertEquals(2, store.notes.size)
        vm.edit(); vm.text("Discard on lock")
        vm.lock()
        assertFalse(vm.state.value.unlocked)
        assertTrue(vm.state.value.notes.isEmpty())
        assertNull(vm.state.value.editing)
    }
    @Test fun `late unlock completion cannot reopen a locked vault`() {
        val vm = VaultViewModel(store, dispatcher)
        vm.authenticated(); vm.lock(); dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.unlocked)
        assertTrue(vm.state.value.notes.isEmpty())
    }
    @Test fun `timeout clears editor and failed writes do not claim success`() {
        val vm = VaultViewModel(store, dispatcher, timeoutMs = 1000)
        vm.authenticated(); dispatcher.scheduler.runCurrent()
        vm.edit(); vm.text("Unsaved"); store.fail = true; vm.save(); dispatcher.scheduler.runCurrent()
        assertEquals("Unsaved", vm.state.value.editing?.body)
        assertTrue(vm.state.value.message!!.startsWith("Not saved"))
        dispatcher.scheduler.advanceTimeBy(1001); dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.unlocked)
        assertNull(vm.state.value.editing)
    }
}
