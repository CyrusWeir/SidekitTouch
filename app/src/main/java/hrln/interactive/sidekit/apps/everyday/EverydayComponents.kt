package hrln.interactive.sidekit.apps.everyday

import androidx.compose.foundation.layout.Arrangement
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/*
 * Small pieces shared by the "Sidephone · Everyday apps" mocks (Figma section 96:419):
 * Calendar, Mail, Maps, Compass, Files, Health, Web browser, Settings, Banking, Trip Planning.
 */

/** Height of the compact list rows (Files, Settings, Web browser address bar). */
val CompactRowHeight: Dp = 74.dp

/** Gap between the top-aligned title and secondary text of [TopAlignedRow]. */
private val TopAlignedRowGap: Dp = 6.dp

/**
 * The scrolling content column of an everyday home screen: rows separated by
 * `item-outer-gap`, with the scaffold padding that keeps the last row clear of the action bar.
 */
@Composable
fun EverydayContent(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap),
        content = content,
    )
}

/**
 * A short single-line info strip ("November 2026", "Inbox · 2 unread",
 * "Central · Next departures"): a surface tile with the text inset 30 and [top] from the top.
 */
@Composable
fun InfoStrip(
    text: String,
    height: Dp,
    top: Dp,
    modifier: Modifier = Modifier,
    style: TextStyle = SidekitTheme.typography.label,
    color: Color = SidekitTheme.colors.light,
) {
    SidekitTile(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.TopStart) {
        SidekitText(
            text = text,
            modifier = Modifier.padding(start = SidekitTheme.spacing.itemPadding, end = SidekitTheme.spacing.itemPadding, top = top),
            style = style,
            color = color,
        )
    }
}

/**
 * Dual Text item whose texts sit at the top of the row instead of the centre (Mail inbox,
 * Trip Planning departures). The title and secondary text are top-aligned, not baseline-aligned.
 */
@Composable
fun TopAlignedRow(
    title: String,
    secondary: String,
    height: Dp,
    top: Dp,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    SidekitTile(
        modifier = modifier.fillMaxWidth().height(height),
        onClick = onClick,
        contentAlignment = Alignment.TopStart,
    ) {
        Row(
            modifier = Modifier.padding(start = SidekitTheme.spacing.itemPadding, end = SidekitTheme.spacing.itemPadding, top = top),
            horizontalArrangement = Arrangement.spacedBy(TopAlignedRowGap),
        ) {
            SidekitText(title, style = titleStyle)
            SidekitText(
                secondary,
                Modifier.weight(1f, fill = false),
                style = SidekitTheme.typography.caption,
                color = SidekitTheme.colors.surfaceA6,
            )
        }
    }
}

/**
 * The Dual action bar every everyday app ends with: "← [back]" and "[forward] →".
 * Neither destination is mocked, so callers usually pass no-ops.
 */
fun dualArrowActions(
    back: String,
    forward: String,
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
): List<SideAction> = listOf(SideAction.back("back", back, onBack), SideAction.forward("forward", forward, onForward))

/** Dual action bar with a plain Secondary (green) right-hand button, as in Maps' "Go". */
fun backAndGoActions(back: String, forward: String, onBack: () -> Unit = {}, onForward: () -> Unit = {}): List<SideAction> =
    listOf(SideAction.back("back", back, onBack), SideAction("go", forward, tone = ActionTone.Secondary, onClick = onForward))
