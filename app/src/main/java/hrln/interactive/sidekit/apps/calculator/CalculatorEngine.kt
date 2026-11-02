package hrln.interactive.sidekit.apps.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** The four binary operators on the keypad. [symbol] is the key label in the mock (65:174). */
enum class Operator(val symbol: String) {
    Divide("/"), Multiply("x"), Subtract("-"), Add("+");

    fun apply(a: BigDecimal, b: BigDecimal): BigDecimal? = when (this) {
        Add -> a.add(b, CalculatorEngine.MATH)
        Subtract -> a.subtract(b, CalculatorEngine.MATH)
        Multiply -> a.multiply(b, CalculatorEngine.MATH)
        Divide -> if (b.signum() == 0) null else a.divide(b, CalculatorEngine.MATH)
    }
}

/** A key on the Calculator keypad. */
sealed interface CalcKey {
    val label: String

    data class Digit(val value: Int) : CalcKey {
        init { require(value in 0..9) }
        override val label get() = value.toString()
    }
    data class Op(val operator: Operator) : CalcKey {
        override val label get() = operator.symbol
    }
    data object Decimal : CalcKey { override val label = "." }
    data object Equals : CalcKey { override val label = "=" }
    data object Percent : CalcKey { override val label = "%" }
    data object Sign : CalcKey { override val label = "+/-" }
    data object Backspace : CalcKey { override val label = "←" }
    data object Clear : CalcKey { override val label = "AC" }
}

/**
 * Immutable calculator state.
 *
 * @property display what the header shows.
 * @property accumulator left operand waiting for [pendingOp].
 * @property entering true while the user is typing into [display]; false when it shows a result.
 * @property repeatOp / [repeatOperand] let repeated `=` re-apply the last operation.
 */
data class CalculatorState(
    val display: String = "0",
    val accumulator: BigDecimal? = null,
    val pendingOp: Operator? = null,
    val entering: Boolean = false,
    val repeatOp: Operator? = null,
    val repeatOperand: BigDecimal? = null,
    val error: Boolean = false,
) {
    val value: BigDecimal get() = if (error) BigDecimal.ZERO else BigDecimal(display)
}

/** Pure reducer for the Calculator app: immediate-execution (left-to-right) arithmetic. */
object CalculatorEngine {
    /** Maximum digits the user can type into one number. */
    const val MAX_INPUT_DIGITS = 12
    /** Significant digits shown for results. */
    const val RESULT_PRECISION = 10
    /** Longest plain result before switching to exponent notation. */
    const val MAX_DISPLAY_CHARS = 12
    const val ERROR = "Error"

    internal val MATH = MathContext(34, RoundingMode.HALF_EVEN)

    fun press(state: CalculatorState, key: CalcKey): CalculatorState {
        if (state.error) {
            return when (key) {
                is CalcKey.Digit, CalcKey.Decimal -> press(CalculatorState(), key)
                else -> CalculatorState()
            }
        }
        return when (key) {
            is CalcKey.Digit -> digit(state, key.value)
            CalcKey.Decimal -> decimal(state)
            is CalcKey.Op -> operator(state, key.operator)
            CalcKey.Equals -> equals(state)
            CalcKey.Percent -> percent(state)
            CalcKey.Sign -> sign(state)
            CalcKey.Backspace -> backspace(state)
            CalcKey.Clear -> CalculatorState()
        }
    }

    fun press(state: CalculatorState, keys: Iterable<CalcKey>): CalculatorState = keys.fold(state, ::press)

    private fun freshEntry(state: CalculatorState) = if (state.pendingOp == null) CalculatorState() else state

    private fun digit(state: CalculatorState, d: Int): CalculatorState {
        if (!state.entering) return freshEntry(state).copy(display = d.toString(), entering = true)
        val display = when (state.display) {
            "0" -> d.toString()
            "-0" -> "-$d"
            else -> if (state.display.count(Char::isDigit) >= MAX_INPUT_DIGITS) return state else state.display + d
        }
        return state.copy(display = display)
    }

    private fun decimal(state: CalculatorState): CalculatorState = when {
        !state.entering -> freshEntry(state).copy(display = "0.", entering = true)
        '.' in state.display -> state
        else -> state.copy(display = state.display + ".")
    }

    private fun operator(state: CalculatorState, op: Operator): CalculatorState {
        val acc = state.accumulator
        val pending = state.pendingOp
        if (pending != null && acc != null) {
            // Operator pressed again before a second operand: just swap the operator.
            if (!state.entering) return state.copy(pendingOp = op)
            val result = pending.apply(acc, state.value) ?: return errorState()
            return state.copy(
                display = format(result), accumulator = result, pendingOp = op, entering = false,
                repeatOp = null, repeatOperand = null,
            )
        }
        return state.copy(
            accumulator = state.value, pendingOp = op, entering = false,
            repeatOp = null, repeatOperand = null,
        )
    }

    private fun equals(state: CalculatorState): CalculatorState {
        val acc = state.accumulator
        val pending = state.pendingOp
        if (pending != null && acc != null) {
            val operand = state.value
            val result = pending.apply(acc, operand) ?: return errorState()
            return CalculatorState(display = format(result), repeatOp = pending, repeatOperand = operand)
        }
        val repeatOp = state.repeatOp
        val repeatOperand = state.repeatOperand
        if (repeatOp != null && repeatOperand != null) {
            val result = repeatOp.apply(state.value, repeatOperand) ?: return errorState()
            return state.copy(display = format(result), entering = false)
        }
        return state.copy(display = format(state.value), entering = false)
    }

    /** `%` divides by 100, or after `+`/`-` takes that percentage of the left operand (50 + 10% = 55). */
    private fun percent(state: CalculatorState): CalculatorState {
        val value = state.value
        val acc = state.accumulator
        val result = if (acc != null && (state.pendingOp == Operator.Add || state.pendingOp == Operator.Subtract)) {
            acc.multiply(value, MATH).divide(HUNDRED, MATH)
        } else {
            value.divide(HUNDRED, MATH)
        }
        // With an operator pending the percentage becomes the right operand, so keep it as "entered".
        return state.copy(display = format(result), entering = state.pendingOp != null)
    }

    private fun sign(state: CalculatorState): CalculatorState {
        // After an operator the display still shows the left operand; +/- starts a negative entry.
        // At a bare 0 it does the same, so the next digits are typed as a negative number.
        if (!state.entering && (state.pendingOp != null || state.display == "0")) {
            return state.copy(display = "-0", entering = true)
        }
        val display = if (state.display.startsWith("-")) state.display.removePrefix("-") else "-" + state.display
        return state.copy(display = display)
    }

    private fun backspace(state: CalculatorState): CalculatorState {
        if (!state.entering) return state
        val trimmed = state.display.dropLast(1)
        val display = if (trimmed.isEmpty() || trimmed == "-") "0" else trimmed
        return state.copy(display = display)
    }

    private fun errorState() = CalculatorState(display = ERROR, error = true)

    /** Formats a result: at most [RESULT_PRECISION] significant digits, no trailing zeros. */
    fun format(value: BigDecimal): String {
        val rounded = value.round(MathContext(RESULT_PRECISION, RoundingMode.HALF_UP)).stripTrailingZeros()
        if (rounded.signum() == 0) return "0"
        val plain = rounded.toPlainString()
        return if (plain.length <= MAX_DISPLAY_CHARS) plain else rounded.toString().replace("E+", "e").replace("E", "e")
    }

    private val HUNDRED = BigDecimal(100)
}
