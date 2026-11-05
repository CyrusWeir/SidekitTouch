package hrln.interactive.sidekit.apps.clock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private class Memory : ClockStore {
        var saved = ClockData()
        override fun read() = saved
        override fun write(data: ClockData) { saved = data }
    }
    @After fun cleanup() { scope.cancel() }
    @Test fun `timer starts pauses and resets through the bar`() {
        val store = Memory()
        val repo = ClockRepository(store, scope, Dispatchers.Unconfined)
        compose.setContent { SidekitPreviewFrame { ClockScreen({}, repo) } }
        compose.onNodeWithText("Timer").performClick()
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle { assertNotNull(store.saved.timer.endAt) }
        compose.onNodeWithText("Pause").performClick()
        compose.runOnIdle { assertNull(store.saved.timer.endAt); assertTrue(store.saved.timer.remaining > 0) }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(ClockTimer(), store.saved.timer) }
    }
    @Test fun `stopwatch records a lap and pauses through the bar`() {
        val store = Memory()
        val repo = ClockRepository(store, scope, Dispatchers.Unconfined)
        compose.setContent { SidekitPreviewFrame { ClockScreen({}, repo) } }
        compose.onNodeWithText("Stopwatch").performClick()
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText("Lap").performClick()
        compose.onNodeWithText("Pause").performClick()
        compose.runOnIdle { assertEquals(1, store.saved.stopwatch.laps.size); assertNull(store.saved.stopwatch.startedAt) }
        compose.onRoot().captureRoboImage("screenshots/clock_stopwatch.png")
    }
    @Test fun `alarm list opens selected alarm and new alarm editor`() {
        val store = Memory().apply { saved = ClockData(alarms = listOf(ClockAlarm(id = "test", hour = 7, minute = 30, days = setOf(1,2,3,4,5)))) }
        val repo = ClockRepository(store, scope, Dispatchers.Unconfined)
        var selected = ""
        compose.setContent { SidekitPreviewFrame { ClockScreen({ selected = it }, repo) } }
        compose.onNodeWithText("07:30").performClick()
        compose.runOnIdle { assertEquals("test", selected) }
        compose.onNodeWithText("New alarm").performClick()
        compose.runOnIdle { assertEquals("new", selected) }
        compose.onRoot().captureRoboImage("screenshots/clock_alarms.png")
    }
    @Test fun `swipe delete hides an alarm and Undo restores it`() {
        val store = Memory().apply { saved = ClockData(alarms = listOf(ClockAlarm(id = "test", hour = 7, minute = 30))) }
        val repo = ClockRepository(store, scope, Dispatchers.Unconfined)
        compose.setContent { SidekitPreviewFrame { ClockScreen({}, repo) } }
        compose.onNodeWithText("07:30").performTouchInput { swipeLeft(startX = 400f, endX = 250f) }
        compose.waitForIdle()
        val rowY = compose.onNodeWithText("07:30").fetchSemanticsNode().boundsInRoot.center.y
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 30f, rowY)) }
        compose.onNodeWithText("07:30").assertDoesNotExist()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("07:30").assertIsDisplayed()
        compose.runOnIdle { assertEquals("test", store.saved.alarms.single().id) }
    }

}
