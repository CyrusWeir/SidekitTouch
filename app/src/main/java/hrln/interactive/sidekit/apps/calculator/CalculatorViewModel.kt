package hrln.interactive.sidekit.apps.calculator

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CalculatorViewModel : ViewModel() {
    private val _state = MutableStateFlow(CalculatorState())
    val state: StateFlow<CalculatorState> = _state.asStateFlow()

    fun press(key: CalcKey) = _state.update { CalculatorEngine.press(it, key) }
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
