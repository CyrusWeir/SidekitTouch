package hrln.interactive.sidekit.apps.apps

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * The installed apps Sidekit may launch: all of them, except Sidekit itself. A sibling build
 * (`.dev`) stays, since launching that is an ordinary app launch, not recursion.
 */
internal fun launchableApps(apps: List<LaunchableApp>, selfPackage: String): List<LaunchableApp> =
    apps.filterNot { it.component.packageName == selfPackage }

/** An installed app's own icon, rasterised off the main thread and kept while it stays in the row. */
@Composable
internal fun rememberAppIcon(source: AppsSource, app: LaunchableApp): Bitmap? {
    val icon by produceState<Bitmap?>(null, source, app.key) { value = source.icon(app) }
    return icon
}

/** The search field both app lists start with, so the two match down to the placeholder. */
@Composable
internal fun AppSearchField(query: String, onQuery: (String) -> Unit) {
    SidekitTextField(query, onQuery, placeholder = "Search apps", singleLine = true, focusOnKeypad = true)
}

/**
 * One installed app as a list row: its own icon, its label, an optional [secondary] note, and the
 * link arrow that says a tap leads somewhere. Shared by the Apps screen and Sideport's Add app
 * picker so an app list looks the same everywhere Sidekit shows phone apps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppRow(
    app: LaunchableApp,
    source: AppsSource,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val icon = rememberAppIcon(source, app)
    SidekitItem(
        title = app.label,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
        ),
        secondary = secondary,
        showLinkArrow = true,
        leading = {
            icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(SidekitTheme.spacing.iconSize)) }
        },
    )
}
