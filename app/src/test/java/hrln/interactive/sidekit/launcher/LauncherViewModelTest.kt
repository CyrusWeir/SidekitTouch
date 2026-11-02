package hrln.interactive.sidekit.launcher

import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class LauncherViewModelTest {
    private fun vm() = LauncherViewModel(now = { LocalTime.of(21, 19) }, tick = false)

    @Test fun `clock is 12 hour without leading zero`() {
        assertEquals("9:19", formatClock(LocalTime.of(9, 19)))
        assertEquals("9:19", formatClock(LocalTime.of(21, 19)))
        assertEquals("12:05", formatClock(LocalTime.of(0, 5)))
        assertEquals("9:19", vm().state.value.time)
    }

    @Test fun `starts on the grid and long-press toggles list and back`() {
        val vm = vm()
        assertEquals(LauncherLayout.Grid, vm.state.value.layout)
        vm.toggleLayout()
        assertEquals(LauncherLayout.List, vm.state.value.layout)
        vm.toggleLayout()
        assertEquals(LauncherLayout.Grid, vm.state.value.layout)
    }

    @Test fun `control centre opens and closes`() {
        val vm = vm()
        vm.setControlCentreOpen(true)
        assertTrue(vm.state.value.controlCentreOpen)
        vm.setControlCentreOpen(false)
        assertFalse(vm.state.value.controlCentreOpen)
    }

    @Test fun `initial toggles do not invent system state`() {
        val on = vm().state.value.toggles.filterValues { it }.keys
        assertTrue(on.isEmpty())
    }

    @Test fun `toggles flip independently`() {
        val vm = vm()
        vm.setToggle(QuickToggle.Airplane, true)
        vm.setToggle(QuickToggle.Wifi, false)
        assertTrue(vm.state.value.toggles.getValue(QuickToggle.Airplane))
        assertFalse(vm.state.value.toggles.getValue(QuickToggle.Wifi))
        assertFalse(vm.state.value.toggles.containsKey(QuickToggle.Bluetooth))
    }

    @Test fun `levels are clamped to 0 to 1`() {
        val vm = vm()
        vm.setVolume(1.5f); vm.setBrightness(-0.2f)
        assertEquals(1f, vm.state.value.volume, 0f)
        assertEquals(0f, vm.state.value.brightness, 0f)
    }
}

class SubAppTest {
    @Test fun `routes are unique`() {
        assertEquals(SubApp.entries.size, SubApp.entries.map { it.route }.toSet().size)
    }

    @Test fun `dock matches the mock and leads the list view`() {
        assertEquals(listOf(SubApp.Sidemessage, SubApp.Sidenotes, SubApp.Sideticks, SubApp.Phonebook), SubApp.dock)
        assertEquals(SubApp.dock + SubApp.Translate, SubApp.listOrder.take(5))
        assertEquals(SubApp.entries.toSet(), SubApp.listOrder.toSet())
        assertEquals(SubApp.entries.size, SubApp.listOrder.size)
    }
}
