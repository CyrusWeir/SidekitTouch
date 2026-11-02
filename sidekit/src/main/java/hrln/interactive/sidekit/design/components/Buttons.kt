package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** Line Button "Button Variant" (62:554). */
enum class LineButtonVariant { Primary, Secondary, Destructive }

// The Action Button (62:342) and Action Button Frame (62:148) are the shared action bar:
// see hrln.interactive.sidekit.design.action.SideAction and ActionBarHost.

/** Line Button (62:554): full-width, icon + left-aligned label. */
@Composable
fun SidekitLineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SidekitIcon? = null,
    variant: LineButtonVariant = LineButtonVariant.Primary,
) {
    val color = when (variant) {
        LineButtonVariant.Primary -> SidekitTheme.colors.primaryA1
        LineButtonVariant.Secondary -> SidekitTheme.colors.surfaceA3
        LineButtonVariant.Destructive -> SidekitTheme.colors.dangerA1
    }
    SidekitTile(modifier = modifier.fillMaxWidth(), color = color, onClick = onClick, contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
        ) {
            if (icon != null) SidekitIconImage(icon)
            SidekitText(text)
        }
    }
}
