package hrln.interactive.sidekit.apps.sideticks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideticksViewModelTest {
    @Test fun `ticking moves an item to the ticked section and back`() {
        val vm = SideticksViewModel()
        vm.toggle(1)
        assertTrue(vm.state.value.ticked.any { it.id == 1 })
        assertFalse(vm.state.value.open.any { it.id == 1 })
        vm.toggle(1)
        assertTrue(vm.state.value.open.any { it.id == 1 })
    }

    @Test fun `clear all ticked removes only ticked items and hides the section`() {
        val vm = SideticksViewModel()
        vm.setShowTicked(true)
        val open = vm.state.value.open
        vm.clearTicked()
        assertEquals(open, vm.state.value.ticks)
        assertTrue(vm.state.value.ticked.isEmpty())
        assertFalse(vm.state.value.showTicked)
    }

    @Test fun `confirming a draft adds it to the top`() {
        val vm = SideticksViewModel()
        vm.startAdding(); vm.updateDraft(" Apples ")
        vm.confirmDraft()
        assertEquals("Apples", vm.state.value.ticks.first().title)
        assertFalse(vm.state.value.ticks.first().ticked)
        assertNull(vm.state.value.draft)
    }

    @Test fun `blank draft or cancel adds nothing`() {
        val vm = SideticksViewModel()
        val before = vm.state.value.ticks
        vm.startAdding(); vm.updateDraft("  "); vm.confirmDraft()
        vm.startAdding(); vm.updateDraft("x"); vm.cancelAdding()
        assertEquals(before, vm.state.value.ticks)
        assertNull(vm.state.value.draft)
    }

    @Test fun `draft edits are ignored when not adding`() {
        val vm = SideticksViewModel()
        vm.updateDraft("x")
        assertNull(vm.state.value.draft)
    }
}
