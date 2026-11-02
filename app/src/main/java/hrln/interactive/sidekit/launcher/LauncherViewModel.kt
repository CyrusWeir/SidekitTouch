package hrln.interactive.sidekit.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Launcher Grid view (53:1129) and Launcher List View mini (36:2). */
enum class LauncherLayout { Grid, List }

/** Control centre toggles, in mock order (53:657, 53:674). System state is refreshed while the panel is visible. */
enum class QuickToggle(val label: String) {
    Airplane("Airplane mode"),
    Wifi("Wi-Fi"),
    Bedtime("Bedtime"),
    Silent("Silent"),
    MobileData("Mobile data"),
    Bluetooth("Bluetooth"),
    Flashlight("Flashlight"),
    QrScanner("QR scanner"),
}

data class LauncherUiState(
    val time: String = "9:19",
    val layout: LauncherLayout = LauncherLayout.Grid,
    val controlCentreOpen: Boolean = false,
    val toggles: Map<QuickToggle, Boolean> = emptyMap(),
    val volume: Float = 0f,
    val brightness: Float = 0.5f,
)

private val ClockFormat = DateTimeFormatter.ofPattern("h:mm")

/** The launcher header clock: 12-hour, no leading zero ("9:19"). */
fun formatClock(time: LocalTime): String = time.format(ClockFormat)

class LauncherViewModel(
    private val now: () -> LocalTime = LocalTime::now,
    tick: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(LauncherUiState(time = formatClock(now())))
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    init {
        if (tick) viewModelScope.launch {
            _state.subscriptionCount.collectLatest { observers ->
                if (observers > 0) while (isActive) {
                    _state.update { it.copy(time = formatClock(now())) }
                    delay(1_000)
                }
            }
        }
    }

    fun loadPreferences(context: android.content.Context) {
        _state.update { it.copy(layout = if (hrln.interactive.sidekit.apps.settings.SidekitPreferences(context).listLayout) LauncherLayout.List else LauncherLayout.Grid) }
    }

    fun toggleLayout() = _state.update {
        it.copy(layout = if (it.layout == LauncherLayout.Grid) LauncherLayout.List else LauncherLayout.Grid)
    }

    fun setControlCentreOpen(open: Boolean) = _state.update { it.copy(controlCentreOpen = open) }

    fun setToggle(toggle: QuickToggle, on: Boolean) = _state.update { it.copy(toggles = it.toggles + (toggle to on)) }

    fun setVolume(value: Float) = _state.update { it.copy(volume = value.coerceIn(0f, 1f)) }

    fun setBrightness(value: Float) = _state.update { it.copy(brightness = value.coerceIn(0f, 1f)) }
}
