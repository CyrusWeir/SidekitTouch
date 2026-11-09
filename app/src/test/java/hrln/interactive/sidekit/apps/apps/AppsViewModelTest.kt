package hrln.interactive.sidekit.apps.apps

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    @Test fun `Sidekit cannot launch itself, but can launch its dev build`() {
        val app = { pkg: String -> LaunchableApp(ComponentName(pkg, "Main"), Process.myUserHandle(), pkg) }
        val apps = listOf(app("hrln.interactive.sidekit"), app("hrln.interactive.sidekit.dev"), app("com.example.chat"))
        assertEquals(
            listOf("hrln.interactive.sidekit.dev", "com.example.chat"),
            launchableApps(apps, selfPackage = "hrln.interactive.sidekit").map { it.component.packageName }
        )
    }

    @Test fun `search matches names and numeric keypad sequences`() {
        assertTrue(matchesApp("Firefox", "fire"))
        assertTrue(matchesApp("Firefox", "3473"))
        assertFalse(matchesApp("Firefox", "Maps"))
    }

    @Test fun `visible collection gets package changes and stops upstream on leaving`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val apps = MutableStateFlow(listOf(LaunchableApp(ComponentName("example", "Main"), Process.myUserHandle(), "Firefox")))
            var observing = false
            val source = object : AppsSource {
                override val apps = apps.onStart { observing = true }.onCompletion { observing = false }
                override suspend fun icon(app: LaunchableApp): Bitmap? = null
                override fun launch(app: LaunchableApp) {}
                override fun info(app: LaunchableApp) {}
            }
            val vm = AppsViewModel(source)
            val collector = backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(observing)
            vm.query.value = "3473"
            runCurrent()
            assertEquals(1, vm.state.value.apps.size)
            apps.value = emptyList()
            runCurrent()
            assertTrue(vm.state.value.apps.isEmpty())
            collector.cancel()
            runCurrent()
            assertFalse(observing)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
