package hrln.interactive.sidekit.apps.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import hrln.interactive.sidekit.design.input.T9Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small preferences, also consumed by the launcher and the activity's existing keypad. */
class SidekitPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("sidekit_settings", Context.MODE_PRIVATE)
    var listLayout: Boolean
        get() = prefs.getBoolean("list_layout", false)
        set(value) { prefs.edit().putBoolean("list_layout", value).apply() }
    var inputMode: T9Mode
        get() = T9Mode.entries.firstOrNull { it.name == prefs.getString("input_mode", null) } ?: T9Mode.Predictive
        set(value) { prefs.edit().putString("input_mode", value.name).apply() }
    fun applyInputDefault(keypad: hrln.interactive.sidekit.design.input.Keypad) {
        keypad.finishTyping()
        keypad.engine.setMode(inputMode, object : hrln.interactive.sidekit.design.input.T9Buffer {
            override val text: CharSequence = ""
            override val selectionStart = 0
            override val selectionEnd = 0
            override fun replace(start: Int, end: Int, text: String, selectStart: Int, selectEnd: Int) {}
        })
    }
    var dockComponent: String?
        get() = prefs.getString("dock_component", null)
        set(value) { prefs.edit().putString("dock_component", value).apply() }
}

enum class SettingKey { WiFi, Bluetooth, Display, Sounds, Battery, Storage, DateTime, Privacy }
data class SettingRow(val key: SettingKey, val label: String, val value: String)
data class SettingsUiState(val rows: List<SettingRow> = listOf(
    SettingRow(SettingKey.WiFi, "Wi-Fi", "System settings"),
    SettingRow(SettingKey.Bluetooth, "Bluetooth", "System settings"),
    SettingRow(SettingKey.Display, "Display", "Brightness"),
    SettingRow(SettingKey.Sounds, "Sounds", "Volume"),
    SettingRow(SettingKey.Battery, "Battery", "Usage"),
    SettingRow(SettingKey.Storage, "Storage", "Device storage"),
    SettingRow(SettingKey.DateTime, "Date & time", "System settings"),
    SettingRow(SettingKey.Privacy, "Privacy", "Permissions"),
))
class SettingsViewModel : ViewModel() {
    val state = MutableStateFlow(SettingsUiState()).asStateFlow()
}

fun SettingKey.settingsAction(): String = when (this) {
    SettingKey.WiFi -> android.provider.Settings.ACTION_WIFI_SETTINGS
    SettingKey.Bluetooth -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
    SettingKey.Display -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
    SettingKey.Sounds -> android.provider.Settings.ACTION_SOUND_SETTINGS
    SettingKey.Battery -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
    SettingKey.Storage -> android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
    SettingKey.DateTime -> android.provider.Settings.ACTION_DATE_SETTINGS
    SettingKey.Privacy -> android.provider.Settings.ACTION_PRIVACY_SETTINGS
}
