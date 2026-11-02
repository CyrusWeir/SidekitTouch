package hrln.interactive.sidekit.apps.files

import android.content.IntentSender
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private class FakeFilesSource : FilesSource {
    val entries = mutableListOf(
        FileEntry(Uri.parse("content://media/1"), "z.txt", "text/plain", 10, 1),
        FileEntry(Uri.parse("content://media/2"), "a.txt", "text/plain", 20, 2),
    )
    var deleteResult: DeleteResult = DeleteResult.Deleted
    override suspend fun list(location: FilePlace) = entries
    override suspend fun count(kind: MediaKind) = entries.size
    override suspend fun search(query: String, folders: List<SavedFolder>) = entries.filter { it.name.contains(query, true) }
    override suspend fun delete(entry: FileEntry): DeleteResult {
        if (deleteResult == DeleteResult.Deleted) entries.removeAll { it.key == entry.key }
        return deleteResult
    }
    override fun savedFolders() = emptyList<SavedFolder>()
    override fun addFolder(tree: Uri): SavedFolder? = null
    override fun removeFolder(folder: SavedFolder) = Unit
    override fun space() = 50L to 100L
}

private class MemoryFilesPrefs(override var sort: FileSort = FileSort.Newest) : FilesPrefs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var source: FakeFilesSource
    private lateinit var vm: FilesViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeFilesSource()
        vm = FilesViewModel(source, MemoryFilesPrefs()) { true }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `home counts and storage come from source`() {
        vm.refreshHome()
        scope.testScheduler.runCurrent()
        assertEquals(2, vm.home.value.counts[MediaKind.Documents])
        assertEquals(50L, vm.home.value.freeBytes)
        assertEquals(100L, vm.home.value.totalBytes)
        assertTrue(vm.home.value.mediaAllowed)
    }

    @Test fun `sort applies to listing and persists preference`() {
        vm.open(FilePlace.Media(MediaKind.Documents))
        scope.testScheduler.runCurrent()
        assertEquals(listOf("a.txt", "z.txt"), vm.listing.value!!.entries!!.map { it.name })
        vm.setSort(FileSort.Name)
        assertEquals(FileSort.Name, vm.sort.value)
        vm.setSort(FileSort.Largest)
        assertEquals(listOf("a.txt", "z.txt"), vm.listing.value!!.entries!!.map { it.name })
    }

    @Test fun `search debounces and delete updates visible results`() {
        vm.search("a")
        scope.testScheduler.advanceTimeBy(301)
        scope.testScheduler.runCurrent()
        assertEquals(listOf("a.txt"), vm.results.value!!.map { it.name })
        val entry = vm.results.value!!.single()
        vm.askDelete(entry)
        vm.confirmDelete { error("No consent expected") }
        scope.testScheduler.runCurrent()
        assertTrue(vm.results.value!!.isEmpty())
        assertNull(vm.pendingDelete.value)
        assertEquals("Deleted a.txt", vm.notice.value)
    }

    @Test fun `non deletable entry only shows explanation`() {
        val entry = source.entries.first().copy(canDelete = false)
        vm.askDelete(entry)
        assertNull(vm.pendingDelete.value)
        assertEquals("This file can't be deleted here", vm.notice.value)
    }
}
