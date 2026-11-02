package hrln.interactive.sidekit.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import hrln.interactive.sidekit.design.R

/**
 * Inter at its Display optical size. Figma's variable Inter applies optical sizing
 * automatically, and every SideKit text style is 22px or larger, so the mocks render as
 * Inter Display (tighter spacing than text-size Inter).
 */
val Inter = FontFamily(
    Font(R.font.inter_display_regular, FontWeight.Normal),
    Font(R.font.inter_display_medium, FontWeight.Medium),
)

private fun inter(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/**
 * SideKit type scale (Inter Display Regular). Figma has no text-style variables, so sizes are
 * derived from the text-node heights in the mocks (Inter line height ~1.21x).
 */
@Immutable
data class SidekitTypography(
    /** 24° in Sideweather. */
    val display: TextStyle = inter(76, 92),
    /** Large numbers: balance, step count. */
    val numeric: TextStyle = inter(60, 73),
    /** Header frame titles and the launcher clock. */
    val title: TextStyle = inter(50, 61),
    val headline: TextStyle = inter(42, 51),
    /** Item, button and key labels. */
    val body: TextStyle = inter(36, 44),
    val bodySmall: TextStyle = inter(32, 39),
    val label: TextStyle = inter(30, 36),
    val caption: TextStyle = inter(28, 34),
    val small: TextStyle = inter(24, 29),
    val tiny: TextStyle = inter(22, 27),
)
