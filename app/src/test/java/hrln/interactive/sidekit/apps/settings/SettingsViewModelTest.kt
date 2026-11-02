package hrln.interactive.sidekit.apps.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import hrln.interactive.sidekit.design.input.T9Mode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SettingsViewModelTest {
    @Test fun `every system row has a settings destination`() {
        val rows = SettingsViewModel().state.value.rows
        assertEquals(SettingKey.entries.toSet(), rows.map { it.key }.toSet())
        rows.forEach { assertTrue(it.key.settingsAction().startsWith("android.settings.")) }
    }
    @Test fun `defaults and dock selection survive a new preferences instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SidekitPreferences(context)
        prefs.listLayout = true
        prefs.inputMode = T9Mode.Numeric
        prefs.dockComponent = "example/.Main"
        val restored = SidekitPreferences(context)
        assertTrue(restored.listLayout)
        assertEquals(T9Mode.Numeric, restored.inputMode)
        assertEquals("example/.Main", restored.dockComponent)
    }
}
