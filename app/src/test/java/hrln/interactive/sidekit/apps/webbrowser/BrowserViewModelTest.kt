package hrln.interactive.sidekit.apps.webbrowser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MemoryBrowserStore(var saved: BrowserData = BrowserData()) : BrowserStore {
    override fun read() = saved
    override fun write(data: BrowserData) { saved = data }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var store: MemoryBrowserStore
    private lateinit var repository: BrowserRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = MemoryBrowserStore()
        repository = BrowserRepository(store, scope.backgroundScope, dispatcher) { 100L }
        scope.testScheduler.runCurrent()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(): BrowserViewModel {
        val vm = BrowserViewModel(repository)
        scope.backgroundScope.launch(dispatcher) { vm.data.collect {} }
        scope.testScheduler.runCurrent()
        return vm
    }

    @Test fun `typed input becomes safe web addresses or searches`() {
        assertEquals("https://example.com/news", BrowserUrls.resolve("example.com/news"))
        assertTrue(BrowserUrls.resolve("file:///private").startsWith("https://lite.duckduckgo.com/lite/?q=file"))
        assertTrue(BrowserUrls.resolve("quiet phone").startsWith("https://lite.duckduckgo.com/lite/?q="))
        assertEquals("example.com", BrowserUrls.host("https://www.example.com/a"))
    }

    @Test fun `new tab close and undo restore selection and persist`() {
        val vm = vm()
        val first = repository.data.value!!.currentTabId
        val second = vm.run { newTab(); repository.data.value!!.currentTabId }
        assertFalse(first == second)
        vm.open("https://example.com")
        vm.closeTab(second)
        assertEquals(first, repository.data.value!!.currentTabId)
        vm.undo()
        assertEquals(second, repository.data.value!!.currentTabId)
        assertEquals("https://example.com", repository.data.value!!.currentTab!!.url)
        scope.testScheduler.runCurrent()
        assertEquals(repository.data.value, store.saved)
    }

    @Test fun `bookmarks history and zoom are real repository state`() {
        val vm = vm()
        assertTrue(repository.toggleBookmark("https://example.com", "Example"))
        repository.visited("https://example.com", "Example")
        vm.cycleTextZoom()
        assertTrue(repository.data.value!!.isBookmarked("https://example.com"))
        assertEquals(1, repository.data.value!!.history.size)
        assertEquals(150, repository.data.value!!.textZoom)
        vm.clearHistory()
        assertTrue(repository.data.value!!.history.isEmpty())
    }
}
