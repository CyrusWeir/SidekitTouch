package hrln.interactive.sidekit.launcher

import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.assertEquals
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
}

class SubAppTest {
    @Test fun `routes are unique`() {
        assertEquals(SubApp.entries.size, SubApp.entries.map { it.route }.toSet().size)
    }
}
