package hrln.interactive.sidekit.apps.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SettingKey { WiFi, Bluetooth, Display, Sounds, Privacy }

data class SettingRow(val key: SettingKey, val label: String, val value: String)

data class SettingsUiState(
    val wifiConnected: Boolean = true,
    val bluetoothOn: Boolean = true,
) {
    /** The rows of "Settings / Home" (96:891), in mock order. */
    val rows: List<SettingRow>
        get() = listOf(
            SettingRow(SettingKey.WiFi, "Wi-Fi", if (wifiConnected) "Connected" else "Off"),
            SettingRow(SettingKey.Bluetooth, "Bluetooth", if (bluetoothOn) "On" else "Off"),
            SettingRow(SettingKey.Display, "Display", "Brightness"),
            SettingRow(SettingKey.Sounds, "Sounds", "Volume"),
            SettingRow(SettingKey.Privacy, "Privacy", "Permissions"),
        )
}

/**
 * Wi-Fi and Bluetooth rows toggle in place; Display, Sounds and Privacy open sub-pages that
 * aren't mocked, so tapping them does nothing.
 */
class SettingsViewModel : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onRowClick(key: SettingKey) = _state.update {
        when (key) {
            SettingKey.WiFi -> it.copy(wifiConnected = !it.wifiConnected)
            SettingKey.Bluetooth -> it.copy(bluetoothOn = !it.bluetoothOn)
            SettingKey.Display, SettingKey.Sounds, SettingKey.Privacy -> it
        }
    }
}
