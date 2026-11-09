package hrln.interactive.sidekit.apps.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The compact-layout choice, read where the theme is built (MainActivity) so toggling it in
 * Settings re-lays out the whole app on the next frame. The listener mirrors
 * [rememberCaseColour]: the preference is written from another screen, so it has to be observed
 * rather than read once.
 */
@Composable
internal fun rememberCompactLayout(prefs: SidekitPreferences): Boolean {
    var compact by remember(prefs) { mutableStateOf(prefs.compactLayout) }
    DisposableEffect(prefs) {
        val stop = prefs.observeCompactLayout { compact = prefs.compactLayout }
        compact = prefs.compactLayout
        onDispose { stop() }
    }
    return compact
}
