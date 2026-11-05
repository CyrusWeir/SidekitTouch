package hrln.interactive.sidekit.apps.sidemessage

import hrln.interactive.sidekit.apps.phonebook.ContactNames
import hrln.interactive.sidekit.apps.phonebook.Contact
import hrln.interactive.sidekit.apps.phonebook.PhoneNumber
import hrln.interactive.sidekit.apps.phonebook.PhonebookSource
import hrln.interactive.sidekit.apps.phonebook.CallEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class FakeSource(
    var threads: List<RawThread>,
    val messages: MutableMap<Long, MutableList<Message>>,
) : MessageSource {
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    var observers = 0
    var threadQueries = 0
    val limits = ArrayList<Int>()
    val markedRead = ArrayList<Long>()

    override fun threads() = threads.sortedByDescending { it.date }.also { threadQueries++ }
    override fun messages(threadId: Long, limit: Int, before: Message?): List<Message> {
        limits += limit
        return messages[threadId].orEmpty().filter { before == null || MESSAGE_ORDER.compare(it, before) > 0 }.sortedWith(MESSAGE_ORDER).take(limit)
    }
    override fun markRead(threadId: Long) { markedRead += threadId }
    override fun changes(): Flow<Unit> = events.onStart { observers++ }.onCompletion { observers-- }
}

internal class FakeTransport(override var isDefault: Boolean = false) : SmsTransport {
    data class Sent(val address: String, val text: String, val token: Long, val row: Long)
    val sent = ArrayList<Sent>()
    val rows = LinkedHashMap<Long, Pair<String, Delivery>>()
    val deleted = ArrayList<Long>()
    var fail = false
    private var nextRow = 100L

    override fun insertOutgoing(address: String, text: String, date: Long, threadId: Long): Long =
        nextRow++.also { rows[it] = text to Delivery.Sending }
    override fun send(address: String, text: String, token: Long, rowId: Long): Boolean {
        sent += Sent(address, text, token, rowId)
        return !fail
    }
    override fun markRow(rowId: Long, delivery: Delivery) { rows[rowId] = rows.getValue(rowId).first to delivery }
    override fun deleteRow(rowId: Long) { deleted += rowId; rows.remove(rowId) }
    val removed = ArrayList<Message>()
    var deleteSucceeds = true
    override fun delete(message: Message): Boolean {
        removed += message
        if (!deleteSucceeds) return false
        rows.remove(message.rowId)
        return true
    }
}

internal class MemoryPins(var saved: List<String> = emptyList()) : PinStore {
    override fun read() = saved
    override fun write(pins: List<String>) { saved = pins }
}

private class NoContacts : PhonebookSource {
    override fun contacts() = listOf(Contact(7, "Amelie Hart", listOf(PhoneNumber("0400 000 001", "Mobile"))))
    override fun calls(limit: Int) = emptyList<CallEntry>()
    override fun contactChanges(): Flow<Unit> = emptyFlow()
    override fun callChanges(): Flow<Unit> = emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class SidemessageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var source: FakeSource
    private lateinit var transport: FakeTransport
    private lateinit var pins: MemoryPins
    private lateinit var repo: SidemessageRepository
    private var now = 1_000_000L

    private fun msg(text: String, mine: Boolean, date: Long, delivery: Delivery = Delivery.None, id: Long = date) =
        Message(text, mine, date, delivery, "s$id", id)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeSource(
            threads = listOf(
                RawThread(1, listOf("+61400000001"), "Yeah i’ll come over soon", 30, unread = false),
                RawThread(2, listOf("0400000002"), "hi mum", 20, unread = true),
                RawThread(3, listOf("0400000003"), "Yeah should be all good", 10, unread = false),
            ),
            messages = mutableMapOf(
                1L to MutableList(120) { i -> msg("m$i", mine = i % 2 == 0, date = i.toLong() + 1) },
                3L to mutableListOf(msg("Yeah should be all good", false, 10)),
            ),
        )
        transport = FakeTransport()
        pins = MemoryPins(listOf("0400000001"))
        repo = SidemessageRepository(
            source, transport, pins,
            names = ContactNames { if (it.endsWith("01")) "Amelie" else null },
            contactChanges = { emptyFlow() },
            scope = scope.backgroundScope, io = dispatcher, clock = { now },
        )
        scope.testScheduler.runCurrent()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun inboxVm() = SidemessageViewModel(repo, NoContacts(), dispatcher, dispatcher).also { vm ->
        scope.backgroundScope.launch(dispatcher) { vm.inbox.collect {} }
        scope.backgroundScope.launch(dispatcher) { vm.newMessage.collect {} }
        scope.testScheduler.runCurrent()
    }

    private fun conversationVm(addresses: List<String> = listOf("+61400000001"), thread: Long = 1L) =
        ConversationViewModel(repo, addresses, thread, io = dispatcher).also { vm ->
            vm.setAccess(canRead = true, isDefault = false)
            scope.backgroundScope.launch(dispatcher) { vm.state.collect {} }
            scope.testScheduler.runCurrent()
        }

    @Test fun `inbox reads nothing without SMS access`() {
        val vm = inboxVm()
        assertEquals(0, source.threadQueries)
        assertTrue(vm.inbox.value.threads.isEmpty())
    }

    @Test fun `pinned contacts match threads across number formats and leave the list`() {
        val vm = inboxVm()
        vm.setAccess(canRead = true, isDefault = false)
        scope.testScheduler.runCurrent()
        val state = vm.inbox.value
        assertEquals(listOf("Amelie"), state.pinned.map { it.name })
        assertEquals(1L, state.pinned.single().thread?.id)
        assertEquals(listOf(2L, 3L), state.threads.map { it.id })
        assertTrue(state.threads.first().unread)
        assertEquals("0400000002", state.threads.first().name) // no contact: the number
    }

    @Test fun `pins persist and are capped`() {
        val vm = inboxVm()
        vm.pin("0400000002"); vm.pin("+61 400 000 002") // same person, not pinned twice
        scope.testScheduler.runCurrent()
        assertEquals(listOf("0400000001", "0400000002"), pins.saved)
        vm.unpin("0400000001")
        scope.testScheduler.runCurrent()
        assertEquals(listOf("0400000002"), pins.saved)
        (3..9).forEach { vm.pin("04000000$it$it") }
        scope.testScheduler.runCurrent()
        assertEquals(SidemessageRepository.MAX_PINS, pins.saved.size)
    }

    @Test fun `inbox reloads when the provider changes`() {
        val vm = inboxVm()
        vm.setAccess(canRead = true, isDefault = false)
        scope.testScheduler.runCurrent()
        source.threads = source.threads + RawThread(4, listOf("0400000004"), "new!", 40, unread = true)
        source.events.tryEmit(Unit)
        scope.testScheduler.runCurrent()
        assertEquals(4L, vm.inbox.value.threads.first().id)
    }

    @Test fun `new message recognises a typed number`() {
        val vm = inboxVm()
        vm.setContactsAccess(true)
        vm.setQuery("0412 345 678")
        scope.testScheduler.runCurrent()
        assertEquals("0412345678", vm.newMessage.value.number)
        vm.setQuery("amel")
        scope.testScheduler.runCurrent()
        assertNull(vm.newMessage.value.number)
        assertEquals("Amelie Hart", vm.newMessage.value.matches.single().contact.name)
        assertEquals("+61412", SidemessageViewModel.numberFrom("+61 412"))
        assertNull(SidemessageViewModel.numberFrom("12"))
    }

    @Test fun `conversation pages newest first and keeps memory bounded`() {
        val vm = conversationVm()
        var state = vm.state.value
        assertEquals(ConversationViewModel.PAGE, state.messages.size)
        assertEquals("m119", state.messages.first().text)
        assertTrue(state.hasMore)
        vm.loadOlder()
        scope.testScheduler.runCurrent()
        assertEquals(100, vm.state.value.messages.size)
        vm.loadOlder()
        scope.testScheduler.runCurrent()
        state = vm.state.value
        assertEquals(120, state.messages.size)
        assertFalse(state.hasMore)
        assertTrue(source.limits.all { it <= ConversationViewModel.MAX_LOADED + 1 })
        assertEquals("Amelie", state.title)
    }

    @Test fun `history beyond the memory window is reachable and latest can be restored`() {
        source.messages[1L] = MutableList(1100) { i -> msg("m$i", false, i.toLong()) }
        val vm = conversationVm()
        repeat(19) { vm.loadOlder(); scope.testScheduler.runCurrent() }
        assertEquals(1000, vm.state.value.messages.size)
        assertTrue(vm.state.value.hasMore)
        vm.loadOlder(); scope.testScheduler.runCurrent()
        assertEquals("m99", vm.state.value.messages.first().text)
        assertEquals(50, vm.state.value.messages.size)
        assertTrue(vm.state.value.hasNewer)
        vm.loadLatest(); scope.testScheduler.runCurrent()
        assertEquals("m1099", vm.state.value.messages.first().text)
        assertFalse(vm.state.value.hasNewer)
    }

    @Test fun `only the default SMS app marks a thread read`() {
        val vm = conversationVm()
        assertTrue(source.markedRead.isEmpty())
        vm.setAccess(canRead = true, isDefault = true)
        scope.testScheduler.runCurrent()
        assertEquals(listOf(1L), source.markedRead)
    }

    @Test fun `sending when not default shows sending, then sent, then delivered`() {
        val vm = conversationVm()
        vm.startTyping(); vm.updateDraft(" What do u wanna do after? ")
        vm.send()
        scope.testScheduler.runCurrent()
        assertNull(vm.state.value.draft)
        val sent = transport.sent.single()
        assertEquals("What do u wanna do after?", sent.text)
        assertEquals(-1L, sent.row)
        var newest = vm.state.value.messages.first()
        assertEquals(Delivery.Sending, newest.delivery)

        repo.report(sent.token, sent.row, Delivery.Sent)
        scope.testScheduler.runCurrent()
        assertEquals(Delivery.Sent, vm.state.value.messages.first().delivery)

        // The system then writes the row; the in-memory copy merges into it and adds "delivered".
        repo.report(sent.token, sent.row, Delivery.Delivered)
        source.messages.getValue(1L) += msg("What do u wanna do after?", true, now + 500, Delivery.Sent, id = 999)
        source.events.tryEmit(Unit)
        scope.testScheduler.runCurrent()
        newest = vm.state.value.messages.first()
        assertEquals("s999", newest.key)
        assertEquals(Delivery.Delivered, newest.delivery)
        assertEquals(1, vm.state.value.messages.count { it.text == "What do u wanna do after?" })
    }

    @Test fun `the default SMS app writes and updates its own rows`() {
        transport.isDefault = true
        repo.send(listOf("0400000003"), "hello", threadId = 3)
        scope.testScheduler.runCurrent()
        val sent = transport.sent.single()
        assertEquals(100L, sent.row)
        assertTrue(repo.outgoing.value.isEmpty())
        repo.report(sent.token, sent.row, Delivery.Failed)
        assertEquals(Delivery.Failed, transport.rows.getValue(100L).second)

        // Retry replaces the failed row.
        repo.retry(Message("hello", true, 1, Delivery.Failed, "s100", 100), listOf("0400000003"), 3)
        scope.testScheduler.runCurrent()
        assertEquals(listOf(100L), transport.deleted)
        assertEquals(101L, transport.sent.last().row)
    }

    @Test fun `a radio refusal fails the message at once`() {
        transport.fail = true
        repo.send(listOf("0400000003"), "hello", threadId = 3)
        scope.testScheduler.runCurrent()
        assertEquals(Delivery.Failed, repo.outgoing.value.single().delivery)
    }

    @Test fun `provider observers stop when the conversation stops collecting`() {
        val vm = ConversationViewModel(repo, listOf("0400000001"), 1L, io = dispatcher)
        vm.setAccess(true, false)
        val collector = scope.backgroundScope.launch(dispatcher) { vm.state.collect {} }
        scope.testScheduler.runCurrent()
        assertEquals(1, source.observers)
        collector.cancel()
        scope.testScheduler.runCurrent()
        assertEquals(0, source.observers)
    }

    @Test fun `retry targets only the failed group recipient`() {
        repo.retry(Message("hello", true, delivery = Delivery.Failed, address = "0400000002"),
            listOf("0400000001", "0400000002"), 1L)
        scope.testScheduler.runCurrent()
        assertEquals(listOf("0400000002"), transport.sent.map { it.address })
    }

    @Test fun `deleting a message hides it, then removes the row after the undo window`() {
        transport.isDefault = true
        val vm = conversationVm()
        vm.setAccess(canRead = true, isDefault = true)
        scope.testScheduler.runCurrent()
        val target = vm.state.value.messages.first()
        vm.delete(target)
        scope.testScheduler.runCurrent()
        assertFalse(vm.state.value.messages.any { it.key == target.key })
        assertTrue(vm.state.value.canUndo)
        assertTrue(transport.removed.isEmpty())
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        assertEquals(listOf(target.rowId), transport.removed.map { it.rowId })
        assertFalse(vm.state.value.canUndo)
    }

    @Test fun `a deleted message never flashes back when the provider row is gone`() {
        transport.isDefault = true
        val vm = conversationVm()
        vm.setAccess(canRead = true, isDefault = true)
        scope.testScheduler.runCurrent()
        val target = vm.state.value.messages.first()
        vm.delete(target)
        scope.testScheduler.runCurrent()
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        // The row is deleted and the stale page still holds it; it stays hidden until a reload.
        assertEquals(listOf(target.rowId), transport.removed.map { it.rowId })
        assertTrue(repo.pendingKeys.value.contains(target.key))
        assertFalse(vm.state.value.messages.any { it.key == target.key })
        // A reload without the row drops it from the page as usual.
        source.messages.forEach { (_, list) -> list.removeAll { it.key == target.key } }
        source.events.tryEmit(Unit)
        scope.testScheduler.runCurrent()
        assertFalse(vm.state.value.messages.any { it.key == target.key })
    }

    @Test fun `a message whose provider delete fails stays in the conversation`() {
        transport.isDefault = true
        transport.deleteSucceeds = false
        val vm = conversationVm()
        vm.setAccess(canRead = true, isDefault = true)
        scope.testScheduler.runCurrent()
        val target = vm.state.value.messages.first()
        vm.delete(target)
        scope.testScheduler.runCurrent()
        assertFalse(vm.state.value.messages.any { it.key == target.key })
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        // The row is still there, so the message comes back rather than staying invisible.
        assertTrue(vm.state.value.messages.any { it.key == target.key })
        assertFalse(repo.pendingKeys.value.contains(target.key))
    }

    @Test fun `undo restores a message before the window closes`() {
        transport.isDefault = true
        val vm = conversationVm()
        vm.setAccess(canRead = true, isDefault = true)
        scope.testScheduler.runCurrent()
        val target = vm.state.value.messages.first()
        vm.delete(target)
        scope.testScheduler.runCurrent()
        vm.undoDelete()
        scope.testScheduler.runCurrent()
        assertTrue(vm.state.value.messages.any { it.key == target.key })
        assertFalse(vm.state.value.canUndo)
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        assertTrue(transport.removed.isEmpty())
    }

    @Test fun `pending outgoing messages cannot be deleted before the system stores them`() {
        val vm = conversationVm()
        vm.startTyping(); vm.updateDraft("review outgoing"); vm.send()
        scope.testScheduler.runCurrent()
        val pending = vm.state.value.messages.first { it.key.startsWith("p") }
        // Even if the SMS role changes, an in-flight local message cannot be deleted.
        transport.isDefault = true
        vm.delete(pending)
        scope.testScheduler.runCurrent()
        assertFalse(vm.state.value.canUndo)
        assertTrue(vm.state.value.messages.any { it.key == pending.key })
        val sent = transport.sent.single()
        repo.report(sent.token, sent.row, Delivery.Sent)
        source.messages.getValue(1L) += msg("review outgoing", true, now + 500, Delivery.Sent, id = 999)
        source.events.tryEmit(Unit)
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        assertEquals(1, vm.state.value.messages.count { it.text == "review outgoing" })
        assertTrue(transport.removed.isEmpty())
    }

    @Test fun `non default app cannot start a stored message deletion`() {
        val vm = conversationVm()
        val target = vm.state.value.messages.first()
        vm.delete(target)
        scope.testScheduler.runCurrent()
        assertFalse(vm.state.value.canUndo)
        assertTrue(vm.state.value.messages.contains(target))
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        assertTrue(transport.removed.isEmpty())
    }

    @Test fun `replacing a deletion commits only the first and lets the second be undone`() {
        transport.isDefault = true
        val vm = conversationVm()
        val targets = vm.state.value.messages.take(2)
        vm.delete(targets[0])
        scope.testScheduler.runCurrent()
        scope.testScheduler.advanceTimeBy(4_999)
        vm.delete(targets[1])
        scope.testScheduler.runCurrent()
        scope.testScheduler.advanceTimeBy(2)
        scope.testScheduler.runCurrent()
        assertTrue(vm.state.value.canUndo)
        vm.undoDelete()
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS + 1)
        scope.testScheduler.runCurrent()
        assertEquals(listOf(targets[0]), transport.removed)
        assertTrue(vm.state.value.messages.contains(targets[1]))
        assertFalse(vm.state.value.messages.contains(targets[0]))
    }

    @Test fun `undo at the deadline before expiry runs prevents deletion`() {
        transport.isDefault = true
        val vm = conversationVm()
        val target = vm.state.value.messages.first()
        vm.delete(target)
        scope.testScheduler.runCurrent()
        scope.testScheduler.advanceTimeBy(SidemessageRepository.UNDO_WINDOW_MS)
        vm.undoDelete()
        scope.testScheduler.runCurrent()
        assertTrue(vm.state.value.messages.contains(target))
        assertTrue(transport.removed.isEmpty())
    }

    @Test fun `blank messages are not sent`() {
        val vm = conversationVm()
        vm.startTyping(); vm.updateDraft("   "); vm.send()
        scope.testScheduler.runCurrent()
        assertTrue(transport.sent.isEmpty())
        assertNull(vm.state.value.draft)
    }

    @Test fun `a new conversation finds its thread by address`() {
        val vm = conversationVm(listOf("+61 400 000 003"), thread = -1)
        assertEquals(3L, vm.threadId.value)
        assertEquals("Yeah should be all good", vm.state.value.messages.single().text)
        val unknown = conversationVm(listOf("0499999999"), thread = -1)
        assertTrue(unknown.state.value.messages.isEmpty())
    }

    @Test fun `pinning from a conversation`() {
        val vm = conversationVm(listOf("0400000003"), 3)
        assertFalse(vm.state.value.pinned)
        vm.togglePin()
        scope.testScheduler.runCurrent()
        assertTrue(vm.state.value.pinned)
    }

    @Test fun `addresses compare across formats`() {
        assertEquals(Addresses.key("+61 400 000 001"), Addresses.key("0400000001"))
        assertEquals("telstra", Addresses.key("Telstra"))
        assertTrue(Addresses.same(listOf("0400000001", "0400000002"), listOf("+61400000002", "+61400000001")))
    }

    @Test fun `merge leaves conversations without outgoing messages alone`() {
        val list = listOf(msg("a", true, 2), msg("b", false, 1))
        assertTrue(SidemessageRepository.mergeOutgoing(list, emptyList(), listOf("1")) === list)
        val other = listOf(Outgoing(1, "0499999999", "x", 3, Delivery.Sending))
        assertEquals(list, SidemessageRepository.mergeOutgoing(list, other, listOf("0400000001")))
    }
}
