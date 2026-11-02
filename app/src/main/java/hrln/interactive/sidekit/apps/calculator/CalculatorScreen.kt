package hrln.interactive.sidekit.apps.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.components.SidekitHeader
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.calculatorGraph(nav: NavHostController) {
    composable(SubApp.Calculator.route) {
        val vm: CalculatorViewModel = viewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        CalculatorScreen(display = state.display, onKey = vm::press)
    }
}

/** Key rows in 65:174 are 99 high (5 rows + 3 gaps fill the 507 content area; no action bar). */
private val KeyHeight = 99.dp

/** "Calculator" (65:174). */
@Composable
fun CalculatorScreen(display: String, onKey: (CalcKey) -> Unit) {
    SidekitScaffold(
        title = display,
        titleAlign = TextAlign.End,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap),
        ) {
            CalculatorKeypad.rows.forEach { row ->
                SidekitItemRow {
                    row.forEach { key ->
                        SidekitTile(
                            modifier = Modifier.weight(1f).height(KeyHeight),
                            color = key.color(),
                            onClick = { onKey(key) },
                            onClickLabel = key.label,
                        ) {
                            SidekitText(key.label)
                        }
                    }
                }
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
