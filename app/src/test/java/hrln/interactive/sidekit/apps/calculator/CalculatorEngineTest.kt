package hrln.interactive.sidekit.apps.calculator

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorEngineTest {

    /** Presses keys given as their keypad labels, e.g. "1 2 + 3 =". */
    private fun run(keys: String, from: CalculatorState = CalculatorState()): CalculatorState =
        CalculatorEngine.press(from, keys.split(" ").filter { it.isNotEmpty() }.map(::key))

    private fun key(label: String): CalcKey = when (label) {
        in (0..9).map(Int::toString) -> CalcKey.Digit(label.toInt())
        "." -> CalcKey.Decimal
        "=" -> CalcKey.Equals
        "%" -> CalcKey.Percent
        "+/-" -> CalcKey.Sign
        "<" -> CalcKey.Backspace
        "AC" -> CalcKey.Clear
        else -> CalcKey.Op(Operator.entries.first { it.symbol == label })
    }

    private fun display(keys: String) = run(keys).display

    @Test fun startsAtZero() = assertEquals("0", CalculatorState().display)

    @Test fun typingDigitsReplacesLeadingZero() = assertEquals("120", display("0 1 2 0"))

    @Test fun inputIsCappedAtMaxDigits() = assertEquals("123456789012", display("1 2 3 4 5 6 7 8 9 0 1 2 3 4"))

    @Test fun basicOperations() {
        assertEquals("5", display("2 + 3 ="))
        assertEquals("-1", display("2 - 3 ="))
        assertEquals("42", display("6 x 7 ="))
        assertEquals("2.5", display("5 / 2 ="))
    }

    @Test fun chainingEvaluatesLeftToRight() {
        assertEquals("5", display("2 + 3 +"))
        assertEquals("20", display("2 + 3 x 4 ="))
        assertEquals("4", display("1 0 - 2 - 4 ="))
    }

    @Test fun changingOperatorBeforeSecondOperandReplacesIt() = assertEquals("6", display("8 + - 2 ="))

    @Test fun repeatedEqualsRepeatsLastOperation() {
        assertEquals("11", display("5 + 2 = = ="))
        assertEquals("2", display("1 6 / 2 = = ="))
    }

    @Test fun digitAfterResultStartsNewNumber() = assertEquals("7", display("2 + 3 = 7"))

    @Test fun resultCanBeUsedInNextOperation() = assertEquals("10", display("2 + 3 = x 2 ="))

    @Test fun decimals() {
        assertEquals("0.3", display("0 . 1 + 0 . 2 ="))
        assertEquals("0.", display("."))
        assertEquals("1.5", display("1 . . 5"))
        assertEquals("3.75", display("1 . 5 x 2 . 5 ="))
        assertEquals("0.5", display("3 + . 5 = - 3 ="))
    }

    @Test fun divisionRoundsToTenSignificantDigits() {
        assertEquals("0.3333333333", display("1 / 3 ="))
        assertEquals("0.6666666667", display("2 / 3 ="))
    }

    @Test fun trailingZerosAreStripped() = assertEquals("2", display("1 . 5 0 + 0 . 5 ="))

    @Test fun percentDividesByHundred() {
        assertEquals("0.5", display("5 0 %"))
        assertEquals("0.25", display("2 5 %"))
    }

    @Test fun percentAfterAddTakesPercentageOfLeftOperand() {
        assertEquals("5", display("5 0 + 1 0 %"))
        assertEquals("55", display("5 0 + 1 0 % ="))
        assertEquals("180", display("2 0 0 - 1 0 % ="))
    }

    @Test fun percentAfterMultiplyIsAFraction() = assertEquals("10", display("2 0 0 x 5 % ="))

    @Test fun signTogglesEntry() {
        assertEquals("-5", display("5 +/-"))
        assertEquals("5", display("5 +/- +/-"))
        assertEquals("-12", display("+/- 1 2"))
        assertEquals("-0", display("+/-"))
        assertEquals("0", display("+/- ="))
    }

    @Test fun signNegatesResult() = assertEquals("-5", display("2 + 3 = +/-"))

    @Test fun signAfterOperatorStartsNegativeOperand() {
        assertEquals("-0", display("5 x +/-"))
        assertEquals("-15", display("5 x +/- 3 ="))
    }

    @Test fun backspace() {
        assertEquals("12", display("1 2 3 <"))
        assertEquals("0", display("7 <"))
        assertEquals("0", display("7 < <"))
        assertEquals("1.", display("1 . 5 <"))
        assertEquals("0", display("5 +/- <"))
        assertEquals("-1", display("1 2 +/- <"))
    }

    @Test fun backspaceDoesNotEditResults() = assertEquals("5", display("2 + 3 = <"))

    @Test fun divideByZeroShowsError() {
        val state = run("5 / 0 =")
        assertTrue(state.error)
        assertEquals(CalculatorEngine.ERROR, state.display)
        assertEquals(BigDecimal.ZERO, state.value)
    }

    @Test fun divideByZeroWhileChainingShowsError() = assertTrue(run("8 / 0 +").error)

    @Test fun errorRecovery() {
        assertEquals("7", display("1 / 0 = 7"))
        assertEquals("0.", display("1 / 0 = ."))
        assertEquals("0", display("1 / 0 = +"))
        assertFalse(run("1 / 0 = AC").error)
        assertEquals("0", display("1 / 0 = <"))
    }

    @Test fun allClearResetsEverything() {
        assertEquals(CalculatorState(), run("1 2 + 3 AC"))
        assertEquals("4", display("1 2 + 3 AC 4"))
        assertEquals("3", display("9 + 1 = AC 3 ="))
    }

    @Test fun largeResultsUseExponentNotation() {
        assertEquals("1e14", display("1 0 0 0 0 0 0 0 0 0 0 0 x 1 0 0 0 ="))
        assertEquals("1.234567e-7", display("1 . 2 3 4 5 6 7 / 1 0 0 0 0 0 0 0 ="))
    }

    @Test fun exponentResultsCanBeUsedAgain() = assertEquals("2e14", display("1 0 0 0 0 0 0 0 0 0 0 0 x 1 0 0 0 = x 2 ="))

    @Test fun keypadMatchesMockLayout() {
        val labels = CalculatorKeypad.rows.map { row -> row.map { it.label } }
        assertEquals(
            listOf(
                listOf("←", "AC", "%", "/"),
                listOf("7", "8", "9", "x"),
                listOf("4", "5", "6", "-"),
                listOf("1", "2", "3", "+"),
                listOf("+/-", "0", ".", "="),
            ),
            labels,
        )
    }
}
