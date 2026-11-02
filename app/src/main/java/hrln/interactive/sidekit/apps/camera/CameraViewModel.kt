package hrln.interactive.sidekit.apps.camera

import android.net.Uri

data class CameraUiState(
    val flashOn: Boolean = false,
    val recording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val busy: Boolean = false,
    val lastCapture: Uri? = null,
    val error: String? = null,
) {
    val elapsedLabel: String get() = formatElapsed(elapsedSeconds)
    fun toggleFlash() = copy(flashOn = !flashOn)
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
