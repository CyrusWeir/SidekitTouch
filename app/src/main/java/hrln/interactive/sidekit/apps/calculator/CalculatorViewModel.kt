package hrln.interactive.sidekit.apps.calculator

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CalculatorViewModel(private val history: CalculatorHistory? = null) : ViewModel() {
    private val _state = MutableStateFlow(CalculatorState())
    val state: StateFlow<CalculatorState> = _state.asStateFlow()

    fun press(key: CalcKey) {
        val before = _state.value
        val after = CalculatorEngine.press(before, key)
        _state.value = after
        if (key == CalcKey.Equals && !before.error && (before.pendingOp != null || before.repeatOp != null)) {
            val op = before.pendingOp ?: before.repeatOp!!
            val left = before.accumulator?.let(CalculatorEngine::format) ?: before.display
            val right = if (before.pendingOp != null) before.display else CalculatorEngine.format(before.repeatOperand!!)
            history?.add(Calculation("$left ${op.symbol} $right", after.display))
        }
    }
    fun recall(result: String) { if (result != CalculatorEngine.ERROR) _state.value = CalculatorState(display = result) }
}

/** Keypad layout of "Calculator" (65:174), top row first. */
object CalculatorKeypad {
    val rows: List<List<CalcKey>> = listOf(
        listOf(CalcKey.Backspace, CalcKey.Clear, CalcKey.Percent, CalcKey.Op(Operator.Divide)),
        listOf(CalcKey.Digit(7), CalcKey.Digit(8), CalcKey.Digit(9), CalcKey.Op(Operator.Multiply)),
        listOf(CalcKey.Digit(4), CalcKey.Digit(5), CalcKey.Digit(6), CalcKey.Op(Operator.Subtract)),
        listOf(CalcKey.Digit(1), CalcKey.Digit(2), CalcKey.Digit(3), CalcKey.Op(Operator.Add)),
        listOf(CalcKey.Sign, CalcKey.Digit(0), CalcKey.Decimal, CalcKey.Equals),
    )
}
