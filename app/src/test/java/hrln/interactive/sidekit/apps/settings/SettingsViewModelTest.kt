package hrln.interactive.sidekit.apps.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {
    private fun SettingsViewModel.value(key: SettingKey) = state.value.rows.first { it.key == key }.value

    @Test fun `rows match the mock`() {
        val rows = SettingsViewModel().state.value.rows
        assertEquals(listOf("Wi-Fi", "Bluetooth", "Display", "Sounds", "Privacy"), rows.map { it.label })
        assertEquals(listOf("Connected", "On", "Brightness", "Volume", "Permissions"), rows.map { it.value })
    }

    @Test fun `wifi and bluetooth toggle`() {
        val vm = SettingsViewModel()
        vm.onRowClick(SettingKey.WiFi)
        assertEquals("Off", vm.value(SettingKey.WiFi))
        vm.onRowClick(SettingKey.WiFi)
        assertEquals("Connected", vm.value(SettingKey.WiFi))
        vm.onRowClick(SettingKey.Bluetooth)
        assertEquals("Off", vm.value(SettingKey.Bluetooth))
    }

    @Test fun `link rows leave state unchanged`() {
        val vm = SettingsViewModel()
        val before = vm.state.value
        SettingKey.entries.filter { it != SettingKey.WiFi && it != SettingKey.Bluetooth }.forEach(vm::onRowClick)
        assertEquals(before, vm.state.value)
    }
}
