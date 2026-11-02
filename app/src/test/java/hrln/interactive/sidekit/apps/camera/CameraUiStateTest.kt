package hrln.interactive.sidekit.apps.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateTest {
    @Test fun startsWithFlashOffAndNotRecording() {
        val state = CameraUiState()
        assertFalse(state.flashOn)
        assertFalse(state.recording)
        assertEquals("00:00", state.elapsedLabel)
    }

    @Test fun flashToggles() {
        assertTrue(CameraUiState().toggleFlash().flashOn)
        assertFalse(CameraUiState().toggleFlash().toggleFlash().flashOn)
    }

    @Test fun recordingTogglesAndResetsTimer() {
        val recording = CameraUiState().toggleRecording()
        assertTrue(recording.recording)
        val ticked = recording.tick()
        assertEquals("00:01", ticked.elapsedLabel)
        val stopped = ticked.toggleRecording()
        assertFalse(stopped.recording)
        assertEquals(0, stopped.elapsedSeconds)
    }

    @Test fun tickIgnoredWhenNotRecording() = assertEquals(CameraUiState(), CameraUiState().tick())

    @Test fun flashIsIndependentOfRecording() = assertTrue(CameraUiState(flashOn = true).toggleRecording().flashOn)

    @Test fun formatsElapsed() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:59", formatElapsed(59))
        assertEquals("01:00", formatElapsed(60))
        assertEquals("59:59", formatElapsed(3599))
        assertEquals("1:00:00", formatElapsed(3600))
        assertEquals("00:00", formatElapsed(-5))
    }
}
