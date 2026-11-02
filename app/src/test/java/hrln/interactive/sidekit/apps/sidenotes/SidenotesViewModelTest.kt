package hrln.interactive.sidekit.apps.sidenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class MemoryStore(var saved: List<Note>) : NoteStore {
    override fun read() = saved
    override fun write(notes: List<Note>) { saved = notes }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SidenotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var now = 2_000_000_000_000L
    private lateinit var store: MemoryStore
    private lateinit var repo: NotesRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = MemoryStore(SidenotesSampleData.notes)
        repo = NotesRepository(store, scope.backgroundScope, dispatcher) { now++ }
        scope.testScheduler.runCurrent()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun notes() = repo.notes.value!!

    /** A ViewModel whose state is being collected, as it is on screen. */
    private fun vm() = SidenotesViewModel(repo, undoWindowMs = 5_000).also { vm ->
        scope.backgroundScope.launch(dispatcher) { vm.state.collect {} }
        scope.testScheduler.runCurrent()
    }

    @Test fun `loads newest first and persists saves`() {
        assertEquals(SidenotesSampleData.notes.map { it.id }, notes().map { it.id })
        val id = repo.save(null, "Buy milk\n\nand eggs  ")!!
        scope.testScheduler.runCurrent()
        val note = notes().first()
        assertEquals(id, note.id)
        assertEquals("Buy milk\n\nand eggs", note.body)
        assertEquals("Buy milk", note.title)
        assertEquals("and eggs", note.preview)
        assertEquals(notes(), store.saved)
    }

    @Test fun `editing moves a note to the top and unchanged saves are no-ops`() {
        val last = notes().last()
        repo.save(last.id, last.body)
        assertEquals(last, notes().last())
        repo.save(last.id, "Changed")
        assertEquals(last.id, notes().first().id)
        assertEquals(SidenotesSampleData.notes.size, notes().size)
    }

    @Test fun `closing a blank new note stores nothing`() {
        vm().close(NEW_NOTE_ID, "   ")
        assertEquals(SidenotesSampleData.notes, notes())
    }

    @Test fun `closing an emptied note deletes it with undo`() {
        val vm = vm()
        val first = notes().first()
        vm.close(first.id, "")
        scope.testScheduler.runCurrent()
        assertNull(repo.get(first.id))
        assertEquals(first, vm.state.value.recentlyDeleted)
    }

    @Test fun `undo restores a deleted note in place`() {
        val vm = vm()
        val target = notes()[2]
        vm.delete(target.id)
        scope.testScheduler.runCurrent()
        assertEquals(target, vm.state.value.recentlyDeleted)
        assertTrue(vm.state.value.notes.none { it.id == target.id })
        vm.undoDelete()
        scope.testScheduler.runCurrent()
        assertNull(vm.state.value.recentlyDeleted)
        assertEquals(target, notes()[2])
    }

    @Test fun `undo offer expires`() {
        val vm = vm()
        vm.delete(notes().first().id)
        scope.testScheduler.runCurrent()
        scope.testScheduler.advanceTimeBy(5_001)
        scope.testScheduler.runCurrent()
        assertNull(vm.state.value.recentlyDeleted)
    }

    @Test fun `ids are never reused while a deleted note can be restored`() {
        val newest = notes().first()
        repo.delete(newest.id)
        val id = repo.save(null, "fresh")!!
        assertNotEquals(newest.id, id)
        repo.restore(newest)
        val ids = notes().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `file store round-trips unicode and bodies over 64 KB`() {
        val dir = Files.createTempDirectory("sidenotes").toFile()
        try {
            val file = File(dir, "notes.bin")
            val notes = listOf(Note(1, "Hello 👋\nwörld", 10), Note(2, "x".repeat(70_000), 20))
            FileNoteStore(file).write(notes)
            assertEquals(notes, FileNoteStore(file).read())
            assertEquals(emptyList<Note>(), FileNoteStore(File(dir, "missing.bin")).read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
