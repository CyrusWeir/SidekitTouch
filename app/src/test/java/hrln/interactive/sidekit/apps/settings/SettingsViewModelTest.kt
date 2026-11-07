package hrln.interactive.sidekit.apps.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import hrln.interactive.sidekit.design.theme.contentColorFor
import androidx.test.core.app.ApplicationProvider
import hrln.interactive.sidekit.design.input.T9Mode
import org.junit.Assert.*
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SettingsViewModelTest {
    @Test fun `case colour labels have readable contrast`() {
        CaseColour.entries.forEach { colour ->
            val palette = colour.palette()
            listOf(palette.primaryA1, palette.primaryA2, palette.primaryA3).forEach { background ->
                val foreground = palette.contentColorFor(background)
                val lighter = maxOf(background.luminance(), foreground.luminance())
                val darker = minOf(background.luminance(), foreground.luminance())
                assertTrue("${colour.label} contrast", (lighter + 0.05f) / (darker + 0.05f) >= 4.5f)
            }
        }
    }

    /** ΔE (CIE76) of 30 keeps Primary and Secondary from reading as the same colour on one bar. */
    @Test fun `case accents stay distinguishable from the commit colour`() {
        CaseColour.entries.forEach { colour ->
            val palette = colour.palette()
            val distance = colourDistance(palette.primaryA1, palette.successA1)
            assertTrue("${colour.label} primary vs success ΔE $distance", distance >= 30f)
        }
    }

    private fun colourDistance(a: Color, b: Color): Float {
        val (l1, a1, b1) = toLab(a)
        val (l2, a2, b2) = toLab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    /** CIE L*a*b* (D65) so distance tracks how different the colours look, not just their contrast. */
    private fun toLab(color: Color): Triple<Float, Float, Float> {
        fun linear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        val x = (r * 0.4124f + g * 0.3576f + b * 0.1805f) / 0.95047f
        val y = r * 0.2126f + g * 0.7152f + b * 0.0722f
        val z = (r * 0.0193f + g * 0.1192f + b * 0.9505f) / 1.08883f
        fun f(t: Float) = if (t > 0.008856f) t.pow(1f / 3f) else 7.787f * t + 16f / 116f
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    @Test fun `every system row has a settings destination`() {
        val rows = SettingsViewModel().state.value.rows
        assertEquals(SettingKey.entries.toSet(), rows.map { it.key }.toSet())
        rows.forEach { assertTrue(it.key.settingsAction().startsWith("android.settings.")) }
    }
    @Test fun `layout and input mode survive a new preferences instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SidekitPreferences(context)
        prefs.listLayout = true
        prefs.inputMode = T9Mode.Numeric
        val restored = SidekitPreferences(context)
        assertTrue(restored.listLayout)
        assertEquals(T9Mode.Numeric, restored.inputMode)
    }
}
