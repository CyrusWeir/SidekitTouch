package hrln.interactive.sidekit.apps.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Camera UI state. Mock-only: there's no CameraX, so the shutter and recording don't capture.
 * @property elapsedSeconds recording timer shown in "Camera Recording" (71:2114).
 */
data class CameraUiState(
    val flashOn: Boolean = false,
    val recording: Boolean = false,
    val elapsedSeconds: Int = 0,
) {
    val elapsedLabel: String get() = formatElapsed(elapsedSeconds)

    fun toggleFlash() = copy(flashOn = !flashOn)

    /** Video button starts recording; the recording bar stops it and resets the timer. */
    fun toggleRecording() = copy(recording = !recording, elapsedSeconds = 0)

    fun tick() = if (recording) copy(elapsedSeconds = elapsedSeconds + 1) else this
}

/** "00:01" style timer; hours are added once a recording passes 59:59. */
fun formatElapsed(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

class CameraViewModel : ViewModel() {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    private var timer: Job? = null

    fun toggleFlash() = _state.update { it.toggleFlash() }

    fun toggleRecording() {
        _state.update { it.toggleRecording() }
        timer?.cancel()
        timer = if (_state.value.recording) {
            viewModelScope.launch {
                while (isActive) {
                    delay(1_000)
                    _state.update { it.tick() }
                }
            }
        } else {
            null
        }
    }

    /** Shutter: nothing to capture without a camera pipeline. */
    fun takePhoto() = Unit
}
