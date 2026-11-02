package hrln.interactive.sidekit.apps.tripplanning

import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.apps.maps.Place
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TripRepositoryTest {
    @Test fun `trip and saved place survive serialization`() = runBlocking {
        val trip = LocalTripPlanner().plan(TripDraft(" Home ", "Park", "Tomorrow 9am", "Bring water"))
        val data = TripData(listOf(trip), listOf(Place("Park", 1.0, 2.0)))
        assertEquals(data, TripRepository.decode(TripRepository.encode(data)))
        assertEquals("Home", trip.from)
    }
    @Test fun `empty trip is rejected`() = runBlocking {
        assertTrue(runCatching { LocalTripPlanner().plan(TripDraft("", "Park", "", "")) }.isFailure)
    }
    @Test fun `viewmodel saves edits with same id and deletion supports restore`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = TripRepository(Files.createTempDirectory("trips").resolve("trips.json").toFile(), repoScope)
            withContext(Dispatchers.IO) { withTimeout(3_000) { repo.store.state.filterNotNull().first() } }
            val vm = TripViewModel(repo)
            vm.from = "Home"; vm.to = "Park"
            var saved = false
            vm.save { saved = true }; runCurrent()
            assertTrue(saved)
            val first = repo.store.state.value!!.trips.single()
            vm.edit(first); vm.notes = "Water"; vm.save {}; runCurrent()
            val edited = repo.store.state.value!!.trips.single()
            assertEquals(first.id, edited.id)
            assertEquals("Water", edited.notes)
            repo.delete(edited)
            assertTrue(repo.store.state.value!!.trips.isEmpty())
            repo.save(edited)
            assertEquals(edited, repo.store.state.value!!.trips.single())
        } finally { repoScope.cancel(); Dispatchers.resetMain() }
    }
}
