package hrln.interactive.sidekit.apps.calculator

import androidx.compose.foundation.layout.height
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.listFade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

/** Hardware digits are literal numbers; * multiplies, # equals, Delete backspaces. */
fun calculatorHardwareKey(code: Int): CalcKey? = when (code) {
    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> CalcKey.Digit(code - android.view.KeyEvent.KEYCODE_0)
    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> CalcKey.Digit(code - android.view.KeyEvent.KEYCODE_NUMPAD_0)
    android.view.KeyEvent.KEYCODE_STAR, android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> CalcKey.Op(Operator.Multiply)
    android.view.KeyEvent.KEYCODE_POUND, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_EQUALS -> CalcKey.Equals
    android.view.KeyEvent.KEYCODE_PLUS, android.view.KeyEvent.KEYCODE_NUMPAD_ADD -> CalcKey.Op(Operator.Add)
    android.view.KeyEvent.KEYCODE_MINUS, android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> CalcKey.Op(Operator.Subtract)
    android.view.KeyEvent.KEYCODE_SLASH, android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE -> CalcKey.Op(Operator.Divide)
    android.view.KeyEvent.KEYCODE_PERIOD, android.view.KeyEvent.KEYCODE_NUMPAD_DOT -> CalcKey.Decimal
    android.view.KeyEvent.KEYCODE_DEL -> CalcKey.Backspace
    android.view.KeyEvent.KEYCODE_CLEAR -> CalcKey.Clear
    else -> null
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.calculatorGraph(nav: NavHostController) {
    composable(SubApp.Calculator.route) {
        val context = LocalContext.current
        val history = remember { CalculatorHistory.get(context.applicationContext) }
        val vm: CalculatorViewModel = viewModel(factory = remember { viewModelFactory { initializer { CalculatorViewModel(history) } } })
        val state by vm.state.collectAsStateWithLifecycle()
        val entries by history.entries.collectAsStateWithLifecycle()
        val error by history.error.collectAsStateWithLifecycle()
        CalculatorScreen(state.display, vm::press, entries, history::clear, vm::recall, error)
    }
}

@Composable
fun CalculatorScreen(display: String, onKey: (CalcKey) -> Unit, history: List<Calculation> = emptyList(),
    onClearHistory: () -> Unit = {}, onRecall: (String) -> Unit = {}, historyError: String? = null) {
    var page by rememberSaveable { mutableStateOf("keys") }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(page) { if (page == "keys") focus.requestFocus() }
    BackHandler(page != "keys") { page = "keys"; confirmClear = false }
    SidekitScaffold(
        title = if (page == "keys") display else if (page == "history") "History" else "Keypad help",
        titleAlign = if (page == "keys") TextAlign.End else TextAlign.Start,
        modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            if (page != "keys") false else calculatorHardwareKey(event.nativeKeyEvent.keyCode)?.let { key ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0) onKey(key)
                true
            } ?: false
        }.focusable(),
        actions = if (page == "keys") listOf(SideAction("clear", "Clear") { onKey(CalcKey.Clear) }, SideAction("main", "=") { onKey(CalcKey.Equals) })
        else if (confirmClear) listOf(SideAction("back", "Cancel") { confirmClear = false }, SideAction("main", "Clear") { onClearHistory(); confirmClear = false })
        else listOf(SideAction("main", "Back") { page = "keys" }),
        options = when (page) {
            "keys" -> listOf(SideAction("history", "History") { page = "history" }, SideAction("help", "Keypad") { page = "help" })
            "history" -> listOf(SideAction("clear", "Clear history") { confirmClear = true })
            else -> emptyList()
        },
    ) { padding ->
        val list = rememberLazyListState()
        SidekitList(padding, state = list) {
            when (page) {
                "keys" -> CalculatorKeypad.rows.forEachIndexed { index, row -> item {
                    SidekitItemRow(Modifier.listFade(list, index)) {
                        row.forEach { key ->
                            SidekitTile(Modifier.weight(1f).height(SidekitTheme.spacing.itemHeight * 0.7f), color = key.color(), onClick = { onKey(key) }, onClickLabel = key.label) { SidekitText(key.label) }
                        }
                    }
                } }
                "history" -> {
                    historyError?.let { item { SidekitItem(it) } }
                    if (history.isEmpty()) item { SidekitItem("No calculations yet", Modifier.listFade(list, 0)) }
                    history.forEachIndexed { index, entry -> item { SidekitItem(entry.result, Modifier.listFade(list, index), secondary = entry.expression, onClick = { onRecall(entry.result); page = "keys" }) } }
                }
                else -> listOf("0–9 type numbers", "* multiplies · # equals", "Delete backspaces", "Use the operator tiles for +, −, ÷ and decimal.", "Calculations run left to right.").forEachIndexed { index, text -> item { SidekitItem(text, Modifier.listFade(list, index)) } }
            }
        }
    }
}

@Composable
private fun CalcKey.color(): Color {
    val colors = SidekitTheme.colors
    return when (this) {
        CalcKey.Backspace -> colors.dangerA2 // Item "Destructive" colour
        CalcKey.Clear, CalcKey.Percent, is CalcKey.Op -> colors.successA1
        CalcKey.Equals -> colors.primaryA2
        is CalcKey.Digit, CalcKey.Sign, CalcKey.Decimal -> colors.surfaceA3
    }
}

@SidephonePreview
@Composable
private fun CalculatorPreview() = SidekitPreviewFrame { CalculatorScreen("0", {}) }

@SidephonePreview
@Composable
private fun CalculatorResultPreview() = SidekitPreviewFrame { CalculatorScreen("1234.5678", {}) }
