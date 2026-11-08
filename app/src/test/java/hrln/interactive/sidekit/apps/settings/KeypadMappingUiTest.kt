package hrln.interactive.sidekit.apps.settings

import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.input.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Settings > Keypad mapping: the editor, learning a key and the key tester. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeypadMappingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("keypad_mapping_ui_test", Context.MODE_PRIVATE)
    private val chrome = ChromeState()
    private lateinit var keypad: Keypad

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    private fun show(module: KeypadModule = KeypadModule.T9, content: @Composable () -> Unit) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            keypad = remember { Keypad.forTesting(scope, T9Words.Empty, module, KeyMapper.forTesting(prefs) { if (it.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) "Virtual" else "Test keypad" }) }
            SidekitTheme { SidekitCanvas(keypad = keypad, chrome = chrome) { content() } }
        }
        compose.mainClock.advanceTimeBy(1000)
    }

    /** What the activity does with a key: translate it, and say whether it went any further. */
    private fun press(code: Int, device: Int = 1): Boolean {
        var reached = false
        compose.runOnIdle {
            val down = keypad.translate(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0, device, 0))
            val up = keypad.translate(KeyEvent(0, 10, KeyEvent.ACTION_UP, code, 0, 0, device, 0))
            reached = down != null || up != null
        }
        compose.mainClock.advanceTimeBy(500)
        return reached
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.mainClock.advanceTimeBy(1000)
        return compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun turningOnCustomMappingListsEveryControlOfTheModule() {
        show { KeypadMappingScreen({}, { _, _ -> }, {}) }
        compose.onNodeWithText("Automatic").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_automatic.png")
        compose.onNodeWithText("Mapping").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(KeypadModule.T9, keypad.mapper.customModule) }
        compose.onNodeWithText("Custom").assertIsDisplayed()
        KeypadControls.of(KeypadModule.T9).forEach { scrollTo(it.label) }

        // Every module's controls can be remapped, not only T9's.
        scrollTo("Layout").performClick()
        compose.runOnIdle { assertEquals(KeypadModule.Qwerty, keypad.mapper.customModule) }
        KeypadControls.of(KeypadModule.Qwerty).forEach { scrollTo(it.label) }
    }

    @Test fun editorShowsMovedKeysAndResetsThem() {
        show {
            LaunchedEffect(Unit) {
                keypad.useCustomMapping(KeypadModule.T9)
                keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_ENDCALL, PhysicalKey("Test keypad", KeyEvent.KEYCODE_MENU))
                keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_DPAD_UP, PhysicalKey("Test keypad", KeyEvent.KEYCODE_VOLUME_UP))
            }
            KeypadMappingScreen({}, { _, _ -> }, {})
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Menu").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_editor.png")
        // The first key (Back) at the top: rows 0-3 are Mapping, Layout, Key tester and the note.
        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Volume up").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_editor_keys.png")
        scrollTo("Reset T9 keys").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(keypad.mapper.bindings(KeypadModule.T9).isEmpty()) }
        compose.onNodeWithText("Menu").assertDoesNotExist()
    }

    @Test fun learningAKeyCapturesItThenSavesOnTheSecondPress() {
        var done = 0
        show {
            LaunchedEffect(Unit) { keypad.useCustomMapping(KeypadModule.T9) }
            KeyCaptureScreen(KeypadModule.T9, KeypadControls.find(KeypadModule.T9, KeyEvent.KEYCODE_5)!!) { done++ }
        }
        compose.onNodeWithText("Press a key").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_capture.png")
        // Captured: it doesn't act (or reach the screen).
        assertFalse(press(KeyEvent.KEYCODE_F1))
        compose.onNodeWithText("Press it again").assertIsDisplayed()
        compose.onNodeWithText("F1").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_heard.png")
        // While waiting for the second press, other keys act as usual (D-pad to Save, Back...).
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(press(KeyEvent.KEYCODE_F1))
        compose.runOnIdle {
            assertEquals(1, done)
            assertEquals(mapOf(KeyEvent.KEYCODE_5 to PhysicalKey("Test keypad", KeyEvent.KEYCODE_F1)), keypad.mapper.bindings(KeypadModule.T9))
        }
    }

    @Test fun movingAKeyThatAlreadyDoesSomethingSaysWhatChanges() {
        show {
            LaunchedEffect(Unit) { keypad.useCustomMapping(KeypadModule.T9) }
            KeyCaptureScreen(KeypadModule.T9, KeypadControls.find(KeypadModule.T9, KeyEvent.KEYCODE_5)!!) {}
        }
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("It moves from Up", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_moves.png")
    }

    @Test fun theBackKeyCanOnlyBeMovedOnPurpose() {
        var done = 0
        show {
            LaunchedEffect(Unit) { keypad.useCustomMapping(KeypadModule.T9) }
            KeyCaptureScreen(KeypadModule.T9, KeypadControls.find(KeypadModule.T9, KeyEvent.KEYCODE_5)!!) { done++ }
        }
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("This is your Back key", substring = true).assertIsDisplayed()
        // Pressing Back again (to get out) goes back as usual instead of saving it as 5.
        assertTrue(press(KeyEvent.KEYCODE_BACK))
        compose.runOnIdle { assertTrue(keypad.mapper.bindings(KeypadModule.T9).isEmpty()) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(1, done)
            assertEquals(KeyEvent.KEYCODE_BACK, keypad.mapper.bindings(KeypadModule.T9)[KeyEvent.KEYCODE_5]?.keyCode)
        }
    }

    @Test fun captureCanAlwaysBeLeft() {
        var done = 0
        var attempt by mutableIntStateOf(0)
        show {
            key(attempt) { KeyCaptureScreen(KeypadModule.T9, KeypadControls.find(KeypadModule.T9, KeyEvent.KEYCODE_5)!!) { done++ } }
        }
        // The Sidephone's top-left key arrives as the system's Back (from the keypad service).
        assertFalse(press(KeyEvent.KEYCODE_BACK, device = KeyCharacterMap.VIRTUAL_KEYBOARD))
        compose.runOnIdle { assertEquals(1, done) }

        attempt++
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, done) }

        // Nothing pressed: it gives up on its own.
        attempt++
        compose.mainClock.advanceTimeBy(11_000)
        compose.runOnIdle {
            assertEquals(3, done)
            assertTrue(keypad.mapper.bindings(KeypadModule.T9).isEmpty())
        }
    }

    @Test fun keyTesterShowsWhatEachKeySendsAndBecomes() {
        show {
            LaunchedEffect(Unit) {
                keypad.useCustomMapping(KeypadModule.T9)
                keypad.mapper.bind(KeypadModule.T9, KeyEvent.KEYCODE_ENDCALL, PhysicalKey("Test keypad", KeyEvent.KEYCODE_MENU))
            }
            KeyTesterScreen {}
        }
        press(KeyEvent.KEYCODE_5)
        press(KeyEvent.KEYCODE_MENU)
        compose.onNodeWithText("Menu → Options").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keypad_mapping_tester.png")
    }

    @Test fun settingsHasOneKeyboardEntry() {
        var opened = 0
        show { SettingsHome({}, {}, onKeyboard = { opened++ }) }
        scrollTo("Keyboard")
        compose.onNodeWithText("Keys & input").assertIsDisplayed()
        // The old separate keypad rows are gone: one entry leads to the Keyboard page.
        compose.onNodeWithText("Input mode").assertDoesNotExist()
        compose.onNodeWithText("Keypad Back key").assertDoesNotExist()
        compose.onNodeWithText("Keypad mapping").assertDoesNotExist()
        compose.onRoot().captureRoboImage("screenshots/keyboard_settings_home.png")
        compose.onNodeWithText("Keyboard").performClick()
        assertEquals(1, opened)
    }

    @Test fun keyboardPageShowsPreferencesAndDiagram() {
        var mapping = 0
        show(KeypadModule.T9) { KeyboardSettingsScreen({}, onKeypadMapping = { mapping++ }) }
        compose.onNodeWithText("Keyboard").assertIsDisplayed()
        compose.onNodeWithText("Keypad").assertIsDisplayed()
        compose.onNodeWithText("Input mode").assertIsDisplayed()
        compose.onNodeWithText("Keypad Back key").assertIsDisplayed()
        compose.onNodeWithText("Keypad mapping").assertIsDisplayed()
        // Tapping the keypad row cycles the module, and the diagram follows it.
        compose.onNodeWithText("Keypad").performClick()
        compose.runOnIdle { assertEquals(KeypadModule.Qwerty, keypad.module) }
        compose.onNodeWithText("Mini QWERTY").assertIsDisplayed()
        // The diagram is below the preferences: scroll to it and check a key and its caption.
        scrollTo("Tap a key to learn its action.")
        compose.onNodeWithText("QW").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/keyboard_settings_page.png")
        scrollTo("Keypad mapping").performClick()
        assertEquals(1, mapping)
    }
}
