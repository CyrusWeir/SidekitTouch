package hrln.interactive.sidekit.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SystemControlsTest {
    @Test fun `volume tracks external changes only while panel is open`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        var volume = -1f
        val controls = SystemControls(context, { _, _ -> }, { volume = it }, {})
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        controls.start()
        assertEquals(2f / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), volume, 0.001f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1f / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), volume, 0.001f)
        controls.stop()
        val last = volume
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(last, volume, 0f)
    }
}
