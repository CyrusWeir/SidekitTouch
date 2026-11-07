package hrln.interactive.sidekit.apps.sideport.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** Monochrome, so foreign app icons sit with Sidekit's own white symbols. */
private val Monochrome = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A ported app's own icon, in grey, at any size: what the Sideport hub's header draws. Launcher
 * tiles and dock art go through `PortedTile` instead; this is for screens that only need the art.
 */
@Composable
fun PortedAppIcon(app: PortedApp, size: Dp = SidekitTheme.spacing.iconSize, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.packageName) { value = PortedIcons.load(context, app.packageName) }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), app.label, modifier.size(size), colorFilter = Monochrome)
    } else {
        SidekitIconImage(SidekitIcon.DeployedCode, size = size, contentDescription = app.label, modifier = modifier)
    }
}
