package hrln.interactive.sidekit.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SideKit spacing tokens. The first five are Figma variables in the `spacing/` collection;
 * the rest are measured from the component sheets (Item 62:950, Action Button Frame 62:148,
 * Header frame 62:898) because Figma has no variables for them.
 *
 * All values are in canvas units: 1.dp == 1 Figma px on the 480 x 640 Sidephone frame
 * (see [hrln.interactive.sidekit.design.canvas.SidekitCanvas]).
 */
@Immutable
data class SidekitSpacing(
    // Figma variables
    val pagePadding: Dp = 10.dp,
    val pageGap: Dp = 10.dp,
    val itemOuterGap: Dp = 3.dp,
    val actionButtonGap: Dp = 10.dp,
    val actionButtonPadding: Dp = 34.dp,
    // Measured from components
    val itemPadding: Dp = 30.dp,
    val itemContentGap: Dp = 14.dp,
    /** Space above the header title. The Figma frame uses [itemPadding]; compact mode trims it. */
    val headerPaddingTop: Dp = 30.dp,
    val headerHeight: Dp = 103.dp,
    val itemHeight: Dp = 104.dp,
    val actionBarHeight: Dp = 112.dp,
    val iconSize: Dp = 40.dp,
    val tileIconSize: Dp = 44.dp,
) {
    companion object {
        /**
         * The measured Figma sizes, except the top and bottom chrome: the header title sits 14
         * above its line box (a 75 tall header, 28 less than the mock) and the action bar is 12
         * shorter. Slightly tighter insets buy body space on the 480 x 640 display without
         * touching the type scale. Chosen from Settings → Compact layout.
         */
        val Compact: SidekitSpacing = SidekitSpacing(
            headerPaddingTop = 14.dp,
            headerHeight = 75.dp,
            actionBarHeight = 100.dp,
        )
    }
}

@Immutable
data class SidekitShapes(
    val item: RoundedCornerShape = RoundedCornerShape(36.dp),
    val actionButton: RoundedCornerShape = RoundedCornerShape(48.dp),
    val control: RoundedCornerShape = RoundedCornerShape(12.dp),
)
