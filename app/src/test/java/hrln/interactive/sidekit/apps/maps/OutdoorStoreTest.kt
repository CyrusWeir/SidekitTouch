package hrln.interactive.sidekit.apps.maps

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OutdoorStoreTest {
    @Test fun `atomic store survives reload and conflates burst edits`() = runTest {
        val file = Files.createTempDirectory("outdoor").resolve("state.json").toFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = OutdoorStore(file, { it.getInt("n") }, { n: Int -> JSONObject().put("n", n) }, 0, backgroundScope, dispatcher)
        runCurrent()
        repeat(100) { store.edit { it + 1 } }
        runCurrent()
        assertEquals(100, JSONObject(file.readText()).getInt("n"))
        val reopened = OutdoorStore(file, { it.getInt("n") }, { n: Int -> JSONObject().put("n", n) }, 0, backgroundScope, dispatcher)
        runCurrent()
        assertEquals(100, reopened.state.value)
    }
    @Test fun `edits during IO loading merge into saved data`() = runTest {
        val file = Files.createTempFile("loading-store", ".json").toFile().apply { writeText("{\"n\":10}") }
        val store = OutdoorStore(file, { it.getInt("n") }, { n: Int -> JSONObject().put("n", n) }, 0, backgroundScope, StandardTestDispatcher(testScheduler))
        store.edit { it + 1 }
        runCurrent()
        assertEquals(11, store.state.value)
        assertEquals(11, JSONObject(file.readText()).getInt("n"))
    }
    @Test fun `corrupt data is reported rather than crashing`() = runTest {
        val file = Files.createTempFile("bad-store", ".json").toFile().apply { writeText("bad") }
        val store = OutdoorStore(file, { it.getInt("n") }, { n: Int -> JSONObject().put("n", n) }, 0, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(0, store.state.value)
        assertNotNull(store.error.value)
    }
    @Test fun `projection and viewport handle equator poles and antimeridian`() {
        assertEquals(MapPoint(2.0, 2.0), project(Place("Origin", 0.0, 0.0), 2))
        assertTrue(project(Place("Pole", 90.0, 0.0), 14).y.isFinite())
        assertEquals(.25, MapViewport(3.75, 2.0, 2).pan(.5, 0.0).x, .00001)
        val v = MapViewport(123.0, 100.0, 14)
        assertEquals(v, v.zoomBy(1).zoomBy(-1))
    }
}
