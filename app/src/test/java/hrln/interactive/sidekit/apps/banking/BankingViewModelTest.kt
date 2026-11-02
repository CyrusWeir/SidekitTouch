package hrln.interactive.sidekit.apps.banking

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

private class MemoryBankStore(var saved: BankBook = BankBook()) : BankStore {
    override fun read() = saved
    override fun write(book: BankBook) { saved = book }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BankingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var store: MemoryBankStore
    private lateinit var repository: BankingRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = MemoryBankStore()
        repository = BankingRepository(store, scope.backgroundScope, dispatcher, clock = { 123L })
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun unlock() {
        scope.backgroundScope.launch(dispatcher) { repository.unlock() }
        scope.testScheduler.runCurrent()
    }

    @Test fun `locked repository does not decrypt or accept edits`() {
        assertNull(repository.book)
        assertNull(repository.addAccount("Everyday", 100))
        assertEquals(BankingRepository.Load.Locked, repository.state.value)
    }

    @Test fun `payments and income use exact cents and persist`() {
        unlock()
        val id = repository.addAccount("Everyday", 250_000)!!
        repository.record(id, "Grocer", 12_345)
        repository.record(id, "Refund", 2_345, incoming = true)
        assertEquals(240_000L, repository.book!!.balance(id))
        assertEquals(-12_345L, repository.book!!.transactions[1].cents)
        assertEquals(2_345L, repository.book!!.transactions[0].cents)
        scope.testScheduler.runCurrent()
        assertEquals(repository.book, store.saved)
        assertFalse(ManualTracker.isLive)
    }

    @Test fun `account deletion undo restores transactions then lock clears book`() {
        unlock()
        val id = repository.addAccount("Everyday", 1_000)!!
        repository.record(id, "Lunch", 250)
        val vm = BankingViewModel(repository)
        scope.backgroundScope.launch(dispatcher) { vm.state.collect {} }
        scope.testScheduler.runCurrent()
        vm.deleteAccount(id)
        assertTrue(repository.book!!.accounts.isEmpty())
        vm.undo()
        assertEquals(id, repository.book!!.accounts.single().id)
        assertEquals(1, repository.book!!.transactions.size)
        vm.lock()
        assertNull(repository.book)
    }

    @Test fun `amount parsing and binary codec round trip`() {
        assertEquals(1_234L, Money.parse("12.34"))
        assertEquals(-500L, Money.parseBalance("-5.00"))
        assertNull(Money.parse("0"))
        val book = BankBook(listOf(Account(1, "Everyday", 100, 2)), listOf(Transaction(2, 1, "Shop", -50, "Card", 3)), 1)
        assertEquals(book, BankCodec.decode(BankCodec.encode(book)))
    }
}
