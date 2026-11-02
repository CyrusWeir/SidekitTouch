package hrln.interactive.sidekit.apps.phonebook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

private fun contact(id: Long, name: String, vararg numbers: String, starred: Boolean = false) =
    Contact(id, name, numbers.map { PhoneNumber(it, "Mobile") }, starred)

class T9SearchTest {
    private val ann = contact(1, "Ann Lee", "0412 345 678")
    private val zoe = contact(2, "Zoë Kim", "+61 400 111 222")
    private val bob = contact(3, "Bob", "02 9876 5432")
    private val all = listOf(ann, zoe, bob)

    @Test fun `names become keypad digits per word`() {
        assertEquals(listOf("266", "533"), ann.wordKeys)
        assertEquals("266533", ann.fullKey)
        assertEquals(listOf("963", "546"), zoe.wordKeys) // accent folded: ë -> e -> 3
    }

    @Test fun `digits match any word's start or the whole name`() {
        assertEquals(listOf(ann), T9Search.filter(all, "53").map { it.contact }) // "Lee"
        assertEquals(listOf(ann), T9Search.filter(all, "2665").map { it.contact }) // "AnnL"
        assertEquals(listOf(zoe), T9Search.filter(all, "96").map { it.contact })
    }

    @Test fun `number matches come after name matches and carry the number`() {
        // "26" spells both "An" (Ann) and "Bo" (Bob).
        val matches = T9Search.filter(all, "26")
        assertEquals(listOf(ann, bob), matches.filter { it.kind == MatchKind.NamePrefix }.map { it.contact })
        val byNumber = T9Search.filter(all, "9876")
        assertEquals(listOf(bob), byNumber.map { it.contact })
        assertEquals(MatchKind.Number, byNumber.single().kind)
        assertEquals("02 9876 5432", byNumber.single().number?.number)
    }

    @Test fun `empty query lists everyone in order`() {
        assertEquals(all, T9Search.filter(all, "").map { it.contact })
    }

    @Test fun `text search matches names or number digits`() {
        assertEquals(listOf(zoe), T9Search.filterText(all, "zoe").map { it.contact })
        assertEquals(listOf(ann), T9Search.filterText(all, "0412 3").map { it.contact })
        assertTrue(T9Search.filterText(all, "nobody").isEmpty())
    }
}

private class FakePhonebook(var people: List<Contact>, var log: List<CallEntry> = emptyList()) : PhonebookSource {
    val contactEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    var contactQueries = 0
    override fun contacts(): List<Contact> = people.also { contactQueries++ }
    override fun calls(limit: Int) = log.take(limit)
    override fun contactChanges(): Flow<Unit> = contactEvents
    override fun callChanges(): Flow<Unit> = MutableSharedFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhonebookViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val ann = contact(1, "Ann Lee", "0412 345 678", starred = true)
    private val bob = contact(2, "Bob Ray", "02 9876 5432")
    private lateinit var source: FakePhonebook

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakePhonebook(listOf(ann, bob), listOf(CallEntry(9, "+61412345678", null, CallType.Missed, 1L, 0)))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PhonebookViewModel(source, dispatcher, dispatcher).also { vm ->
        scope.backgroundScope.launch(dispatcher) { vm.contactsState.collect {} }
        scope.backgroundScope.launch(dispatcher) { vm.recentsState.collect {} }
        scope.testScheduler.runCurrent()
    }

    @Test fun `nothing is read until contacts are allowed`() {
        val vm = vm()
        assertFalse(vm.contactsState.value.allowed)
        assertTrue(vm.contactsState.value.matches.isEmpty())
        assertEquals(0, source.contactQueries)
        vm.setContactsAccess(true)
        scope.testScheduler.runCurrent()
        assertEquals(listOf(ann, bob), vm.contactsState.value.matches.map { it.contact })
        assertEquals(listOf(ann), vm.contactsState.value.starred)
    }

    @Test fun `keypad digits filter and clear`() {
        val vm = vm()
        vm.setContactsAccess(true)
        "72".forEach(vm::typeDigit) // "Ra" of Bob Ray
        scope.testScheduler.runCurrent()
        assertEquals(listOf(bob), vm.contactsState.value.matches.map { it.contact })
        assertTrue(vm.contactsState.value.starred.isEmpty())
        vm.deleteDigit(); vm.clearQuery()
        scope.testScheduler.runCurrent()
        assertEquals(2, vm.contactsState.value.matches.size)
    }

    @Test fun `contacts reload when they change`() {
        val vm = vm()
        vm.setContactsAccess(true)
        scope.testScheduler.runCurrent()
        source.people = listOf(bob)
        source.contactEvents.tryEmit(Unit)
        scope.testScheduler.runCurrent()
        assertEquals(listOf(bob), vm.contactsState.value.matches.map { it.contact })
    }

    @Test fun `recents resolve names across number formats`() {
        val vm = vm()
        vm.setContactsAccess(true)
        vm.setCallLogAccess(true)
        scope.testScheduler.runCurrent()
        val row = vm.recentsState.value.calls.single()
        assertEquals("Ann Lee", row.name)
        assertEquals(1L, row.contactId)
    }

    @Test fun `recents without permission say so`() {
        val vm = vm()
        assertFalse(vm.recentsState.value.allowed)
    }

    @Test fun `dial pad types, deletes and turns a held 0 into plus`() {
        val vm = vm()
        vm.dialKey('0'); vm.dialPlus(); "61".forEach(vm::dialKey)
        assertEquals("+61", vm.dial.value)
        vm.dialDelete()
        assertEquals("+6", vm.dial.value)
    }

    @Test fun `find contact needs enough digits`() {
        assertNull(PhonebookViewModel.findContact(listOf(ann), "04"))
        assertEquals(ann, PhonebookViewModel.findContact(listOf(ann), "0412345678"))
    }
}
