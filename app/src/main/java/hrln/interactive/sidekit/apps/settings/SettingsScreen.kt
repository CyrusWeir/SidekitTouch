package hrln.interactive.sidekit.apps.settings

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    composable(SubApp.Settings.route) {
        val vm: SettingsViewModel = viewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsScreen(state.rows, onRowClick = vm::onRowClick)
    }
}

/** "Settings / Home" (96:891). "← Home" and "About →" have no mocked destination. */
@Composable
fun SettingsScreen(
    rows: List<SettingRow>,
    onRowClick: (SettingKey) -> Unit,
    onHome: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    SidekitScaffold(
        title = "Settings",
        actions = dualArrowActions("Home", "About", onHome, onAbout),
    ) { padding ->
        EverydayContent(padding) {
            rows.forEach { row ->
                SidekitItem(row.label, Modifier.height(CompactRowHeight), secondary = row.value, onClick = { onRowClick(row.key) })
            }
        }
    }
}

@SidephonePreview
@Composable
private fun SettingsPreview() = SidekitPreviewFrame { SettingsScreen(SettingsUiState().rows, {}) }
