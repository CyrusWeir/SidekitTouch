package hrln.interactive.sidekit.apps.settings

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.input.KeyMapper
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.PhysicalKey
import hrln.interactive.sidekit.design.input.T9Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Custom keypad mapping: what keys turn into, and that a mapping can't trap or leak keys. */
@RunWith(AndroidJUnit4::class)
class KeypadMappingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("keypad_mapping_test", Context.MODE_PRIVATE)

    /** Events from device 7 come from the external keyboard; everything else from the keypad. */
    private fun mapper() = KeyMapper.forTesting(prefs) { if (it.deviceId == 7) "External keyboard" else QIN }
    private fun keypad(mapper: KeyMapper = mapper(), module: KeypadModule = KeypadModule.T9) =
        Keypad.forTesting(CoroutineScope(Dispatchers.Unconfined), T9Words.Empty, module, mapper)

    private fun event(action: Int, code: Int, device: Int = 1, repeat: Int = 0, scan: Int = 0) =
        KeyEvent(100L, 100L + repeat, action, code, repeat, 0, device, scan, 0, 0)

    private fun Keypad.press(code: Int, device: Int = 1): List<Int?> =
        listOf(translate(event(KeyEvent.ACTION_DOWN, code, device))?.keyCode, translate(event(KeyEvent.ACTION_UP, code, device))?.keyCode)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun automaticMappingLeavesEveryKeyAlone() {
        val keypad = keypad()
        keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_5, PhysicalKey(QIN, KeyEvent.KEYCODE_F1))
        assertEquals(listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F1), keypad.press(KeyEvent.KEYCODE_F1))
        val down = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        assertSame(down, keypad.translate(down))
    }

    @Test fun customMappingRewritesOnlyTheKeypadThatWasLearned() {
        val keypad = keypad()
        keypad.useCustomMapping(KeypadModule.T9)
        keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_5, PhysicalKey(QIN, KeyEvent.KEYCODE_F1))
        assertEquals(listOf(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5), keypad.press(KeyEvent.KEYCODE_F1))
        // An external keyboard (or headset) sending the same code is not hijacked.
        assertEquals(listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F1), keypad.press(KeyEvent.KEYCODE_F1, device = 7))
        // Keys that weren't moved keep working.
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP), keypad.press(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun keysWithoutAKeyCodeAreToldApartByScanCode() {
        val keypad = keypad()
        keypad.useCustomMapping(KeypadModule.T9)
        val unknown = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, scan = 250)
        keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_ENDCALL, keypad.mapper.keyOf(unknown))
        assertEquals(KeyEvent.KEYCODE_ENDCALL, keypad.translate(unknown)?.keyCode)
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, keypad.translate(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, scan = 251))?.keyCode)
        assertEquals("Scan code 250", keypad.mapper.keyOf(unknown).label)
    }

    @Test fun aKeyDoesOneThingSoLearningItAgainMovesIt() {
        val mapper = mapper()
        val f1 = PhysicalKey(QIN, KeyEvent.KEYCODE_F1)
        mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_5, f1)
        assertEquals(KeyEvent.KEYCODE_5, mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_6, f1))
        assertEquals(mapOf(KeyEvent.KEYCODE_6 to f1), mapper.bindings(KeypadModule.T9))
        // Each module has its own profile.
        assertTrue(mapper.bindings(KeypadModule.Qwerty).isEmpty())
    }

    @Test fun aPressFinishesAsItStartedWhenTheMappingChangesMidPress() {
        val keypad = keypad()
        keypad.useCustomMapping(KeypadModule.T9)
        keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_5, PhysicalKey(QIN, KeyEvent.KEYCODE_F1))
        assertEquals(KeyEvent.KEYCODE_5, keypad.translate(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1))?.keyCode)
        keypad.mapper.resetAll(KeypadModule.T9)
        assertEquals(KeyEvent.KEYCODE_5, keypad.translate(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, repeat = 1))?.keyCode)
        // No stray "5" release (T9 digits type on release) and no stuck key.
        assertEquals(KeyEvent.KEYCODE_5, keypad.translate(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1))?.keyCode)
        assertEquals(listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F1), keypad.press(KeyEvent.KEYCODE_F1))
    }

    @Test fun aCapturedPressIsSwallowedWholeIncludingAfterCaptureEnds() {
        val keypad = keypad()
        val heard = mutableListOf<PhysicalKey>()
        val stop = keypad.mapper.capture { key, _ -> heard += key; true }
        // A release whose press started before the capture is dropped too.
        assertNull(keypad.translate(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertNull(keypad.translate(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1)))
        stop()
        assertNull(keypad.translate(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, repeat = 1)))
        assertNull(keypad.translate(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1)))
        assertEquals(listOf(PhysicalKey(QIN, KeyEvent.KEYCODE_F1)), heard)
        assertEquals(listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F1), keypad.press(KeyEvent.KEYCODE_F1))
    }

    @Test fun eachEventIsTranslatedOnceAsItTravelsThroughDispatch() {
        val keypad = keypad()
        keypad.useCustomMapping(KeypadModule.T9)
        // A swap: translating twice would turn 5 back into 6.
        keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_5, PhysicalKey(QIN, KeyEvent.KEYCODE_6))
        keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_6, PhysicalKey(QIN, KeyEvent.KEYCODE_5))
        val down = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_6)
        val once = keypad.translate(down)!!
        assertEquals(KeyEvent.KEYCODE_5, once.keyCode)
        assertSame(once, keypad.translate(down))
        assertSame(once, keypad.translate(once))
    }

    @Test fun aCustomMappingFixesTheModuleSoDetectionCantUndoIt() {
        val keypad = keypad(module = KeypadModule.Sundial)
        keypad.useCustomMapping(KeypadModule.T9)
        keypad.observe(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, device = -1))
        keypad.observe(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, device = -1))
        assertEquals(KeypadModule.T9, keypad.module)
        assertTrue(keypad.typing)
        // Back to automatic: the detected module again, and detection works.
        keypad.useCustomMapping(null)
        assertEquals(KeypadModule.Sundial, keypad.module)
        keypad.observe(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, device = -1))
        assertEquals(KeypadModule.Qwerty, keypad.module)
    }

    @Test fun profilesAndTheChoiceSurviveARestart() {
        val first = keypad()
        first.useCustomMapping(KeypadModule.Qwerty)
        first.mapper.bind(KeypadModule.Qwerty, KeyEvent.KEYCODE_SPACE, PhysicalKey("Odd | name", KeyEvent.KEYCODE_F2))
        first.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_5, PhysicalKey(QIN, KeyEvent.KEYCODE_F1))
        val restarted = mapper()
        assertEquals(KeypadModule.Qwerty, restarted.customModule)
        assertEquals(mapOf(KeyEvent.KEYCODE_SPACE to PhysicalKey("Odd | name", KeyEvent.KEYCODE_F2)), restarted.bindings(KeypadModule.Qwerty))
        assertEquals(1, restarted.bindings(KeypadModule.T9).size)
        // A second window's mapper (the Sideport overlay) follows changes made elsewhere.
        first.mapper.reset(KeypadModule.T9, KeyEvent.KEYCODE_5)
        first.useCustomMapping(null)
        assertTrue(restarted.bindings(KeypadModule.T9).isEmpty())
        assertNull(restarted.customModule)
    }

    @Test fun keyNamesAreReadable() {
        assertEquals("F1", PhysicalKey.keyName(KeyEvent.KEYCODE_F1))
        assertEquals("D-pad up", PhysicalKey.keyName(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("5", PhysicalKey.keyName(KeyEvent.KEYCODE_5))
    }

    private companion object {
        const val QIN = "Test keypad"
    }
}
