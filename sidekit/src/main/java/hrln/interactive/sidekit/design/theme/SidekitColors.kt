package hrln.interactive.sidekit.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * SideKit colour tokens. Values are the Figma variables in the `Color/` collection
 * (pulled with `get_variable_defs` from the "Colours" section, node 1:69).
 */
@Immutable
data class SidekitColors(
    val primaryA1: Color = Color(0xFF2C65E0),
    val primaryA2: Color = Color(0xFF5274E4),
    val primaryA3: Color = Color(0xFF6E84E8),
    val successA1: Color = Color(0xFF22946E),
    val successA2: Color = Color(0xFF47D5A6),
    val successA3: Color = Color(0xFF9AE8CE),
    val warningA1: Color = Color(0xFFA87A2A),
    val warningA2: Color = Color(0xFFD7AC61),
    val warningA3: Color = Color(0xFFECD7B2),
    val dangerA1: Color = Color(0xFF9C2121),
    val dangerA2: Color = Color(0xFFD94A4A),
    val dangerA3: Color = Color(0xFFEB9E9E),
    val infoA1: Color = Color(0xFF21498A),
    val infoA2: Color = Color(0xFF4077D1),
    val infoA3: Color = Color(0xFF92B2E5),
    val surfaceA1: Color = Color(0xFF121212),
    val surfaceA2: Color = Color(0xFF282828),
    val surfaceA3: Color = Color(0xFF3F3F3F),
    val surfaceA4: Color = Color(0xFF575757),
    val surfaceA5: Color = Color(0xFF717171),
    val surfaceA6: Color = Color(0xFF8B8B8B),
    val dark: Color = Color(0xFF000000),
    val light: Color = Color(0xFFFFFFFF),
)

/** Luminance above which content on a fill should switch from white to black. */
private const val CONTENT_LUMINANCE = 0.3f

/** Black or white, whichever reads against an arbitrary [background] (e.g. a colour swatch). */
fun readableContentOn(background: Color): Color =
    if (background.luminance() > CONTENT_LUMINANCE) Color(0xFF000000) else Color(0xFFFFFFFF)

/** Keep labels readable when a light case colour replaces the default blue. */
fun SidekitColors.contentColorFor(background: Color): Color =
    if (background in listOf(primaryA1, primaryA2, primaryA3)) readableContentOn(background) else light
