package hrln.interactive.sidekit.apps.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class HealthRepositoryTest {
    @Test fun `ledger serializes baseline reboot identity and history`() {
        val ledger = StepLedger().sample("2026-11-01", 50, 1).sample("2026-11-01", 100, 1).sample("2026-11-02", 20, 2)
        assertEquals(ledger, HealthRepository.decode(HealthRepository.encode(ledger)))
    }
    @Test fun `viewmodel validates goals before updating store`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = HealthRepository(Files.createTempDirectory("health").resolve("steps.json").toFile(), scope)
            withTimeout(3_000) { repo.store.state.filterNotNull().first() }
            val vm = HealthViewModel(repo)
            assertFalse(vm.saveGoal("-1")); assertFalse(vm.saveGoal("walk"))
            assertTrue(vm.saveGoal("9000"))
            assertEquals(9000, repo.store.state.value!!.goal)
        } finally { scope.cancel() }
    }
}
