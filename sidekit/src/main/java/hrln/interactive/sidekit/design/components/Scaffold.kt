package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.HeaderSpec
import hrln.interactive.sidekit.design.action.PublishChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.motion.BodyEntrance
import hrln.interactive.sidekit.design.motion.LocalBodyEntrance
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * Header frame (62:898): 460 x 103, title inset 30/30. Drawn by the shared title host; screens
 * use it directly only for a custom header. The spacing token `headerPaddingTop` shortens it
 * (with `headerHeight`) in compact mode.
 */
@Composable
fun SidekitHeader(
    title: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SidekitTheme.spacing.headerHeight)
            .padding(horizontal = SidekitTheme.spacing.itemPadding)
            .padding(top = SidekitTheme.spacing.headerPaddingTop),
    ) {
        SidekitText(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
            style = SidekitTheme.typography.title,
            textAlign = textAlign,
        )
    }
}

/**
 * A SideKit screen: title at the top, body in the middle, action bar at the bottom.
 *
 * Only [content] (the body) is drawn here. [title] and [actions] are published to the system
 * chrome, which morphs them from the previous screen's, while the body rises in. [options] are
 * what the keypad's Options key (top right) shows in the action bar: settings and less common
 * actions that don't earn a permanent button. Passing a
 * [header] replaces the shared title with custom content drawn in the body (e.g. a search field).
 *
 * [content] receives padding that keeps its last item clear of the action bar.
 */
@Composable
fun SidekitScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    actions: List<SideAction> = emptyList(),
    options: List<SideAction> = emptyList(),
    titleAlign: TextAlign = TextAlign.Start,
    titleModifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    trailingAction: SideAction? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val spacing = SidekitTheme.spacing
    val spec = if (header == null && title != null) HeaderSpec(title, titleAlign, titleModifier) else null
    PublishChrome(spec, actions, options, trailingAction)

    // Previews and screenshots show the arrived state; on device the body plays its entrance.
    val inspection = LocalInspectionMode.current
    val entrance = remember { BodyEntrance(arrived = inspection) }
    LaunchedEffect(entrance) { entrance.play() }
    val rise = BodyEntrance.RISE_DP.dp

    Box(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = spacing.pagePadding, end = spacing.pagePadding, top = spacing.pagePadding),
        ) {
            when {
                header != null -> header()
                title != null -> Spacer(Modifier.height(spacing.headerHeight))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = spacing.pageGap)
                    .graphicsLayer {
                        if (!entrance.listDriven) {
                            val p = entrance.row(0f)
                            alpha = p
                            translationY = (1f - p) * rise.toPx()
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    },
            ) {
                val bottom = if (actions.isNotEmpty()) spacing.actionBarHeight + spacing.pageGap + spacing.pagePadding else spacing.pagePadding
                CompositionLocalProvider(LocalBodyEntrance provides entrance) {
                    content(PaddingValues(bottom = bottom))
                }
            }
        }
    }
}
