package hrln.interactive.sidekit.apps.placeholder

import androidx.compose.runtime.Composable
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.ui.Modifier
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.navigation.SubApp

/**
 * Shown for launcher tiles that have no app mock in Figma yet (see [SubApp.designed]).
 * Deliberately empty: header plus the standard back action, nothing invented.
 */
@Composable
fun PlaceholderAppScreen(app: SubApp, onBack: () -> Unit) {
    SidekitScaffold(
        title = app.label,
        actions = listOf(SideAction.back("back", "Home", onBack)),
    ) { }
}
