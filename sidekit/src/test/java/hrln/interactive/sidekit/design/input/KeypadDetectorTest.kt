package hrln.interactive.sidekit.design.input

import android.view.KeyCharacterMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keypad is told from other input devices by its `gxa535_*` name. */
class KeypadDetectorTest {

    @Test fun acceptsEveryModuleName() {
        for (name in listOf(
            "gxa535_media",
            "gxa535_qwerty",
            "gxa535_t9",
            "gxa535_gamepad",
            "gxa535_phone",
            "gxa535_keyboard"
        )) {
            assertTrue(name, KeypadDetector.isKeypadDevice(name, deviceId = 3))
        }
    }

    @Test fun rejectsOtherDevices() {
        assertFalse(KeypadDetector.isKeypadDevice("mt63xx-accdet Headset", deviceId = 4))
        assertFalse(KeypadDetector.isKeypadDevice("External keyboard", deviceId = 7))
    }

    @Test fun allowsVirtualAndUnnamedEvents() {
        assertTrue(KeypadDetector.isKeypadDevice("Injected", KeyCharacterMap.VIRTUAL_KEYBOARD))
        assertTrue(KeypadDetector.isKeypadDevice(null, deviceId = 3))
    }
}
