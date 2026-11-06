package hrln.interactive.sidekit.apps.sideport.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
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
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small rasterised app icons for launcher tiles, loaded off the main thread and cached. */
private object PortedIcons {
    private const val SIZE = 64
    private val cache = object : LruCache<String, Bitmap>(512 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, pkg: String): Bitmap? = cache.get(pkg) ?: withContext(Dispatchers.IO) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(Canvas(bitmap))
                cache.put(pkg, bitmap)
            }
        }.getOrNull()
    }
}

/** Monochrome, so foreign app icons sit with Sidekit's own white symbols. */
private val Monochrome = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** A ported app's launcher tile: its own icon, in grey, on a Sidekit tile. */
@Composable
fun PortedTile(app: PortedApp, onOpen: (PortedApp) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.packageName) { value = PortedIcons.load(context, app.packageName) }
    SidekitTile(modifier = modifier, onClick = { onOpen(app) }, onClickLabel = app.label) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), app.label, Modifier.size(SidekitTheme.spacing.tileIconSize), colorFilter = Monochrome)
        } else {
            SidekitIconImage(SidekitIcon.DeployedCode, size = SidekitTheme.spacing.tileIconSize, contentDescription = app.label)
        }
    }
}

/** A ported app's monochrome icon alone, for action-bar slots such as a dock button's art. */
@Composable
fun PortedIcon(app: PortedApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.packageName) { value = PortedIcons.load(context, app.packageName) }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), app.label, Modifier.size(SidekitTheme.spacing.iconSize).then(modifier), colorFilter = Monochrome)
    } else {
        SidekitIconImage(SidekitIcon.DeployedCode, size = SidekitTheme.spacing.iconSize, contentDescription = app.label, modifier = modifier)
    }
}
