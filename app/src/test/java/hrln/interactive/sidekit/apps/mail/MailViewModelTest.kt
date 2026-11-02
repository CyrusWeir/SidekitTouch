package hrln.interactive.sidekit.apps.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MemoryMailStore(var saved: List<MailMessage> = emptyList()) : MailStore {
    override fun read() = saved
    override fun write(messages: List<MailMessage>) { saved = messages }
}

private class FakeMailAccount(private val result: MailSendResult) : MailAccount {
    override val configured = false
    val sent = mutableListOf<MailMessage>()
    override suspend fun send(message: MailMessage): MailSendResult { sent += message; return result }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var store: MemoryMailStore
    private lateinit var repository: MailRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = MemoryMailStore()
        repository = MailRepository(store, scope.backgroundScope, dispatcher) { 99L }
        scope.testScheduler.runCurrent()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `draft is persisted locally without an account`() {
        val vm = MailViewModel(repository, FakeMailAccount(MailSendResult.Unavailable))
        vm.saveDraft(null, " a@example.com ", "Hello", " Body ")
        assertEquals(MailFolder.Draft, repository.messages.value!!.single().folder)
        assertEquals("a@example.com", repository.messages.value!!.single().to)
        assertEquals("Body", repository.messages.value!!.single().body)
        scope.testScheduler.runCurrent()
        assertEquals(repository.messages.value, store.saved)
    }

    @Test fun `successful handoff is not described as delivered`() {
        val account = FakeMailAccount(MailSendResult.HandedOff)
        val vm = MailViewModel(repository, account)
        vm.send(null, "a@example.com", "Hi", "Body")
        scope.testScheduler.runCurrent()
        assertEquals(1, account.sent.size)
        assertEquals(MailFolder.HandedOff, repository.messages.value!!.single().folder)
        assertEquals("Mail app opened; sending isn't confirmed", vm.notice.value)
    }

    @Test fun `no mail app leaves outbox message and delete can be undone`() {
        val vm = MailViewModel(repository, FakeMailAccount(MailSendResult.Unavailable))
        vm.send(null, "a@example.com", "Hi", "Body")
        scope.testScheduler.runCurrent()
        val message = repository.messages.value!!.single()
        assertEquals(MailFolder.Outbox, message.folder)
        assertEquals("No mail app. Message kept in Outbox", vm.notice.value)
        vm.delete(message.id)
        assertTrue(repository.messages.value!!.isEmpty())
        vm.undo()
        assertEquals(message, repository.messages.value!!.single())
    }
}
