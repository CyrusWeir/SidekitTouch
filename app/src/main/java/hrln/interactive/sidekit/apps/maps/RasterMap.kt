package hrln.interactive.sidekit.apps.maps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.drawFocusRing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.math.*

data class MapViewport(val x: Double, val y: Double, val zoom: Int = 14) {
    fun pan(dx: Double, dy: Double): MapViewport {
        val n = (1 shl zoom).toDouble()
        return copy(x = ((x + dx) % n + n) % n, y = (y + dy).coerceIn(0.0, n - .00001))
    }
    fun zoomBy(change: Int): MapViewport {
        val next = (zoom + change).coerceIn(2, 18)
        val scale = 2.0.pow(next - zoom)
        return MapViewport(x * scale, y * scale, next)
    }
}

@OptIn(FlowPreview::class)
@Composable
fun RasterMap(center: Place, current: Place?, destination: Place?, zoomChange: Int, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { TileRepository(File(context.cacheDir, "map-tiles")) }
    var viewport by remember(center) { mutableStateOf(project(center, 14).let { MapViewport(it.x, it.y) }) }
    var loaded by remember { mutableStateOf<Map<TileKey, ImageBitmap>>(emptyMap()) }
    var canvasSize by remember { mutableStateOf(IntSize(460, 263)) }
    LaunchedEffect(zoomChange) { if (zoomChange != 0) viewport = viewport.zoomBy(if (zoomChange > 0) 1 else -1) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The D-pad moves focus past the map like any row; OK on the map hands it the arrows to pan
    // with, and OK or Back hands them back.
    var focused by remember { mutableStateOf(false) }
    var panning by remember { mutableStateOf(false) }
    BackHandler(enabled = panning) { panning = false }
    var tilesMissing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(tilesMissing, panning) {
        onStatus(when {
            tilesMissing == null -> return@LaunchedEffect
            tilesMissing == true -> "Some tiles unavailable · location and search still work"
            panning -> "Panning · d-pad moves the map; OK or Back to stop"
            else -> "Map ready · drag, or OK to pan with the d-pad; Options to zoom"
        })
    }
    val colors = SidekitTheme.colors
    WhileVisible(repository) {
        try {
            snapshotFlow { viewport to canvasSize }.debounce(180).collectLatest { (v, size) ->
                val n = 1 shl v.zoom
                val left = floor(v.x - size.width / 512.0).toInt()
                val right = floor(v.x + size.width / 512.0).toInt()
                val top = floor(v.y - size.height / 512.0).toInt()
                val bottom = floor(v.y + size.height / 512.0).toInt()
                val tiles = mutableMapOf<TileKey, ImageBitmap>()
                var missing = false
                for (x in left..right) for (y in top..bottom) {
                    if (y !in 0 until n) continue
                    val key = TileKey(v.zoom, (x % n + n) % n, y)
                    val bitmap = repository.tile(key)
                    if (bitmap != null) tiles[key] = bitmap else missing = true
                }
                loaded = tiles
                tilesMissing = missing
            }
        } finally { repository.clearMemory(); loaded = emptyMap() }
    }
    SidekitTile(Modifier.fillMaxWidth().height(263.dp)) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) panning = false }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (e.nativeKeyEvent.repeatCount == 0) panning = !panning; true }
                    Key.DirectionLeft -> panning.also { if (it) viewport = viewport.pan(-.5, 0.0) }
                    Key.DirectionRight -> panning.also { if (it) viewport = viewport.pan(.5, 0.0) }
                    Key.DirectionUp -> panning.also { if (it) viewport = viewport.pan(0.0, -.5) }
                    Key.DirectionDown -> panning.also { if (it) viewport = viewport.pan(0.0, .5) }
                    Key.Plus, Key.Equals -> { viewport = viewport.zoomBy(1); true }
                    Key.Minus -> { viewport = viewport.zoomBy(-1); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(Unit) {
                var pinch = 1f
                detectTransformGestures { _, pan, zoom, _ ->
                    viewport = viewport.pan(-pan.x / 256.0, -pan.y / 256.0)
                    pinch *= zoom
                    if (pinch > 1.5f || pinch < .67f) { viewport = viewport.zoomBy(if (pinch > 1) 1 else -1); pinch = 1f }
                }
            }) {
            drawRect(colors.surfaceA2)
            val v = viewport
            val n = 1 shl v.zoom
            fun wrapX(x: Double): Double = ((x - v.x + n / 2.0 + n) % n) - n / 2.0
            loaded.forEach { (key, image) -> if (key.zoom == v.zoom) drawImage(image, topLeft = Offset((size.width / 2 + wrapX(key.x.toDouble()) * 256).toFloat(), (size.height / 2 + (key.y - v.y) * 256).toFloat())) }
            fun marker(place: Place, color: androidx.compose.ui.graphics.Color) {
                val p = project(place, v.zoom)
                val at = Offset((size.width / 2 + wrapX(p.x) * 256).toFloat(), (size.height / 2 + (p.y - v.y) * 256).toFloat())
                drawCircle(colors.light, 9.dp.toPx(), at); drawCircle(color, 6.dp.toPx(), at)
            }
            current?.let { marker(it, colors.primaryA1) }
            destination?.let { marker(it, colors.dangerA1) }
            if (focused) drawFocusRing(1f)
        }
        Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).background(colors.surfaceA1).clickable {
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) } catch (_: android.content.ActivityNotFoundException) { }
        }) {
            SidekitText("© OpenStreetMap contributors", style = SidekitTheme.typography.tiny, modifier = Modifier.padding(SidekitTheme.spacing.itemOuterGap))
        }
    }
}
