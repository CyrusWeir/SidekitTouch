package hrln.interactive.sidekit.design.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalSidekitColors = staticCompositionLocalOf { SidekitColors() }
private val LocalSidekitTypography = staticCompositionLocalOf { SidekitTypography() }
private val LocalSidekitSpacing = staticCompositionLocalOf { SidekitSpacing() }
private val LocalSidekitShapes = staticCompositionLocalOf { SidekitShapes() }

@Composable
fun SidekitTheme(
    colors: SidekitColors = SidekitColors(),
    typography: SidekitTypography = SidekitTypography(),
    spacing: SidekitSpacing = SidekitSpacing(),
    shapes: SidekitShapes = SidekitShapes(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSidekitColors provides colors,
        LocalSidekitTypography provides typography,
        LocalSidekitSpacing provides spacing,
        LocalSidekitShapes provides shapes,
        LocalIndication provides SidekitPressIndication,
        LocalTextSelectionColors provides TextSelectionColors(colors.primaryA2, colors.primaryA2.copy(alpha = 0.4f)),
        content = content,
    )
}

object SidekitTheme {
    val colors: SidekitColors
        @Composable @ReadOnlyComposable get() = LocalSidekitColors.current
    val typography: SidekitTypography
        @Composable @ReadOnlyComposable get() = LocalSidekitTypography.current
    val spacing: SidekitSpacing
        @Composable @ReadOnlyComposable get() = LocalSidekitSpacing.current
    val shapes: SidekitShapes
        @Composable @ReadOnlyComposable get() = LocalSidekitShapes.current
}
