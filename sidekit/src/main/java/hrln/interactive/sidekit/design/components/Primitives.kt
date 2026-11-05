package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

internal val LocalSidekitContentColor = compositionLocalOf { Color.White }

@Composable
fun SidekitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SidekitTheme.typography.body,
    color: Color = LocalSidekitContentColor.current,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
) {
    // TextStyle.copy merges every attribute; do it once per distinct style, not every recomposition.
    val resolved = remember(style, color, textAlign, textDecoration) {
        style.copy(color = color, textAlign = textAlign ?: style.textAlign, textDecoration = textDecoration)
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SidekitIconImage(
    icon: SidekitIcon,
    modifier: Modifier = Modifier,
    size: Dp = SidekitTheme.spacing.iconSize,
    tint: Color = LocalSidekitContentColor.current,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(icon.drawable),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
