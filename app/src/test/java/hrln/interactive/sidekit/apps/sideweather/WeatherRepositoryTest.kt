package hrln.interactive.sidekit.apps.sideweather

import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.apps.maps.Place
import hrln.interactive.sidekit.apps.maps.currentLocationName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class WeatherRepositoryTest {
    private fun payload() = JSONObject("""{"current":{"temperature_2m":20,"apparent_temperature":19,"weather_code":0,"wind_speed_10m":16,"time":"2026-11-02T12:00"},"daily":{"time":["2026-11-02"],"temperature_2m_max":[25],"temperature_2m_min":[15],"precipitation_probability_max":[10]},"hourly":{"time":["2026-11-02T11:00","2026-11-02T12:00","2026-11-02T13:00"],"temperature_2m":[19,20,21],"weather_code":[0,0,3]}}""")
    @Test fun `fresh cache avoids network and stale failed refresh retains disk forecast`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val directory = Files.createTempDirectory("weather").toFile()
            var calls = 0
            var now = 10_000L
            var fail = false
            val repo = WeatherRepository(directory, scope, { calls++; if (fail) error("offline"); payload() }, { now })
            val place = Place("Test", -33.8, 151.2)
            val first = repo.refresh(place)
            assertEquals(first, repo.refresh(place))
            assertEquals(1, calls)
            now += 30 * 60_000
            fail = true
            runCatching { repo.refresh(place) }
            assertEquals(2, calls)
            val reopened = WeatherRepository(directory, scope, { error("must not fetch") }, { now })
            assertEquals(10_000L, reopened.cached(place)!!.received)
            assertEquals("68°", first.current(place, true).temperature)
            assertEquals(2, first.hourly(false).size)
            assertEquals("25° / 15°", first.daily(false).single().detail)
        } finally { scope.cancel() }
    }
    @Test fun `viewmodel exposes saved forecast offline and stops with visibility job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val directory = Files.createTempDirectory("weather-vm").toFile()
            var now = 10_000L
            var fail = false
            val repo = WeatherRepository(directory, scope, { if (fail) error("offline"); payload() }, { now })
            val place = Place("Test", 1.0, 2.0)
            repo.refresh(place)
            now += 2_000_000; fail = true
            val vm = WeatherViewModel(repo)
            val job = launch { vm.visible(place) }
            withTimeout(3_000) { vm.status.first { it.startsWith("Offline") } }
            assertNotNull(vm.forecast.value)
            job.cancelAndJoin()
            assertTrue(job.isCompleted)
        } finally { scope.cancel() }
    }
    @Test fun `current location keeps one entry and takes its city name`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = WeatherRepository(Files.createTempDirectory("weather-here").toFile(), scope, { payload() })
            val sydney = Place("Sydney", -33.8, 151.2)
            repo.select(sydney)
            repo.select(Place("Current location · Madrid", 40.4, -3.7))
            val fix = Place(currentLocationName(null), 41.4, 2.2)
            repo.select(fix)
            repo.select(sydney)
            repo.rename(fix, currentLocationName("Barcelona"))
            val settings = withTimeout(3_000) { repo.settings.state.first { s -> s?.places?.any { it.name.endsWith("Barcelona") } == true } }!!
            assertEquals(listOf("Current location · Barcelona", "Sydney"), settings.places.map { it.name })
            assertEquals(sydney.key, settings.selected)
        } finally { scope.cancel() }
    }
}
