package hrln.interactive.sidekit.apps.calculator

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalculatorInputTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `physical keypad digits star and pound calculate without T9 text entry`() {
        var state by mutableStateOf(CalculatorState())
        compose.setContent { SidekitTheme { SidekitCanvas { CalculatorScreen(state.display, { state = CalculatorEngine.press(state, it) }) } } }
        compose.onRoot().performKeyInput {
            pressKey(Key.Seven)
            pressKey(Key(KeyEvent.KEYCODE_STAR))
            pressKey(Key.Six)
            pressKey(Key.Pound)
        }
        compose.onNodeWithText("42").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.Nine); pressKey(Key.Backspace) }
        compose.runOnIdle { assertEquals("0", state.display) }
    }
    @Test fun `history is reachable through options and selecting it restores a result`() {
        val chrome = ChromeState()
        var recalled: String? = null
        compose.setContent { SidekitTheme { SidekitCanvas(chrome = chrome) { CalculatorScreen("0", {}, listOf(Calculation("6 x 7", "42")), onRecall = { recalled = it }) } } }
        compose.runOnIdle { chrome.toggleOptions() }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("42").performClick()
        compose.runOnIdle { assertEquals("42", recalled) }
    }
    @Test fun `hardware keys map decimal clear and operator keys`() {
        assertEquals(CalcKey.Decimal, calculatorHardwareKey(KeyEvent.KEYCODE_PERIOD))
        assertEquals(CalcKey.Clear, calculatorHardwareKey(KeyEvent.KEYCODE_CLEAR))
        assertEquals(CalcKey.Op(Operator.Divide), calculatorHardwareKey(KeyEvent.KEYCODE_NUMPAD_DIVIDE))
        assertNull(calculatorHardwareKey(KeyEvent.KEYCODE_BACK))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorHistoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private class MemoryStore(var data: List<Calculation> = emptyList()) : CalculationStore {
        override fun read() = data
        override fun write(history: List<Calculation>) { data = history }
    }
    @Test fun `history records expressions results and repeated equals and survives reopening`() {
        val store = MemoryStore()
        val history = CalculatorHistory(store, scope.backgroundScope, dispatcher)
        val vm = CalculatorViewModel(history)
        listOf(CalcKey.Digit(6), CalcKey.Op(Operator.Multiply), CalcKey.Digit(7), CalcKey.Equals, CalcKey.Equals).forEach(vm::press)
        scope.testScheduler.runCurrent()
        assertEquals(Calculation("42 x 7", "294"), store.data.first())
        val reopened = CalculatorHistory(store, scope.backgroundScope, dispatcher)
        scope.testScheduler.runCurrent()
        assertEquals(history.entries.value, reopened.entries.value)
    }
    @Test fun `clear before load does not resurrect history and history is bounded`() {
        val store = MemoryStore(listOf(Calculation("old", "1")))
        val history = CalculatorHistory(store, scope.backgroundScope, dispatcher)
        history.clear()
        scope.testScheduler.runCurrent()
        assertTrue(store.data.isEmpty())
        repeat(60) { history.add(Calculation(it.toString(), it.toString())) }
        scope.testScheduler.runCurrent()
        assertEquals(50, store.data.size)
    }
    @Test fun `new input after result starts a new calculation`() {
        val vm = CalculatorViewModel()
        listOf(CalcKey.Digit(2), CalcKey.Op(Operator.Add), CalcKey.Digit(3), CalcKey.Equals, CalcKey.Digit(7), CalcKey.Equals).forEach(vm::press)
        assertEquals("7", vm.state.value.display)
    }
}
