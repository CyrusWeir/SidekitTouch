package hrln.interactive.sidekit.apps.sidemessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidemessageViewModelTest {
    @Test fun `pinned contacts are not repeated in the inbox`() {
        val state = SidemessageUiState()
        assertTrue(state.threads.none { it.contact in state.pinned })
        assertEquals(listOf("Josie", "Ari", "Fio"), state.threads.map { it.contact })
    }

    @Test fun `sending appends a sent message and closes the compose box`() {
        val vm = SidemessageViewModel()
        vm.startTyping(); vm.updateDraft(" What do u wanna do after? ")
        vm.send("Amelie")
        val last = vm.state.value.conversation("Amelie").messages.last()
        assertEquals(Message("What do u wanna do after?", fromMe = true), last)
        assertNull(vm.state.value.draft)
    }

    @Test fun `sending to a thread moves it to the top of the inbox`() {
        val vm = SidemessageViewModel()
        vm.startTyping(); vm.updateDraft("hey"); vm.send("Fio")
        assertEquals("Fio", vm.state.value.threads.first().contact)
        assertEquals("hey", vm.state.value.threads.first().preview)
    }

    @Test fun `blank messages are not sent`() {
        val vm = SidemessageViewModel()
        val before = vm.state.value.conversations
        vm.startTyping(); vm.updateDraft("   "); vm.send("Amelie")
        assertEquals(before, vm.state.value.conversations)
        assertNull(vm.state.value.draft)
    }

    @Test fun `unknown contacts get an empty conversation`() {
        assertTrue(SidemessageUiState().conversation("Nobody").messages.isEmpty())
    }
}
