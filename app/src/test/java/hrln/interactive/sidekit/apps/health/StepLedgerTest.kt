package hrln.interactive.sidekit.apps.health

import org.junit.Assert.*
import org.junit.Test

class StepLedgerTest {
    @Test fun `first observation establishes baseline rather than counting lifetime steps`() {
        val ledger = StepLedger().sample("2026-11-02", 10_000, 3)
        assertEquals(0L, ledger.today)
        assertEquals(350L, ledger.sample("2026-11-02", 10_350, 3).today)
    }
    @Test fun `reboot retains earlier steps even when new counter exceeds old counter`() {
        val ledger = StepLedger().sample("2026-11-02", 100, 3).sample("2026-11-02", 300, 3)
        val rebooted = ledger.sample("2026-11-02", 400, 4)
        assertEquals(600L, rebooted.today)
        assertEquals(650L, rebooted.sample("2026-11-02", 450, 4).today)
    }
    @Test fun `counter reset is detected even without a boot identifier`() {
        val ledger = StepLedger().sample("2026-11-02", 100, -1).sample("2026-11-02", 200, -1)
        assertEquals(105L, ledger.sample("2026-11-02", 5, -1).today)
    }
    @Test fun `missed rollover preserves history and labels shifted steps approximate`() {
        val ledger = StepLedger().sample("2026-11-02", 100, 3).sample("2026-11-02", 350, 3)
        val next = ledger.sample("2026-11-03", 500, 3)
        assertEquals(150L, next.today)
        assertEquals(StepDay("2026-11-02", 250, true), next.history.single())
        assertTrue(next.approximate)
        assertEquals(next, next.sample("2026-11-03", 500, 3))
    }
}
