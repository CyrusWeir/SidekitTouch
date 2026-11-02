package hrln.interactive.sidekit.apps.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.backAndGoActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class MapsDestination(val name: String, val detail: String)

object MapsSampleData {
    // Placeholder content from "Maps / Home" (96:563).
    val destination = MapsDestination("Central Station", "8 min walk · 600 m")
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.mapsGraph(nav: NavHostController) {
    liveMapsGraph(nav)
}

/** "Maps / Home" (96:563). "← Search" and "Go" have no mocked destination. */
@Composable
fun MapsScreen(destination: MapsDestination, onSearch: () -> Unit = {}, onGo: () -> Unit = {}) {
    SidekitScaffold(
        title = "Maps",
        actions = backAndGoActions("Search", "Go", onSearch, onGo),
    ) { padding ->
        EverydayContent(padding) {
            NeighbourhoodMap(Modifier.fillMaxWidth().height(263.dp))
            // "Destination preview" (96:626): 119 high, texts at y 18 and 68.
            SidekitTile(Modifier.fillMaxWidth().height(119.dp), contentAlignment = Alignment.TopStart) {
                Column(
                    Modifier.padding(start = SidekitTheme.spacing.itemPadding, top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SidekitText(destination.name)
                    SidekitText(destination.detail, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
                }
            }
        }
    }
}

/**
 * "Editable neighbourhood map" (96:616). The map art can't be exported from Figma, so the street
 * grid and walking route are redrawn from its geometry (460 x 263).
 */
@Composable
private fun NeighbourhoodMap(modifier: Modifier = Modifier) {
    val colors = SidekitTheme.colors
    Box(
        modifier
            .clip(SidekitTheme.shapes.item)
            .semantics { contentDescription = "Map: walking route from your location to Central Station" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = 1.dp.toPx()
            drawRect(colors.surfaceA2)
            // Main streets, 24 wide.
            listOf(73f, 218f, 358f).forEach { x -> drawRect(colors.surfaceA3, Offset(x * u, 0f), Size(24 * u, size.height)) }
            listOf(38f, 128f, 213f).forEach { y -> drawRect(colors.surfaceA3, Offset(0f, y * u), Size(size.width, 24 * u)) }
            // Lanes, 8 wide.
            drawRect(colors.surfaceA4, Offset(281 * u, 0f), Size(8 * u, size.height))
            drawRect(colors.surfaceA4, Offset(0f, 181 * u), Size(size.width, 8 * u))
            // Walking route.
            val route = Path().apply {
                moveTo(230 * u, 215 * u)
                lineTo(230 * u, 140 * u)
                lineTo(370 * u, 140 * u)
                lineTo(370 * u, 56 * u)
            }
            drawPath(route, colors.primaryA1, style = Stroke(width = 12 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // You (start) and destination pins.
            drawCircle(colors.light, radius = 10 * u, center = Offset(230 * u, 215 * u))
            drawCircle(colors.light, radius = 15 * u, center = Offset(370 * u, 56 * u))
            drawCircle(colors.primaryA1, radius = 11 * u, center = Offset(370 * u, 56 * u))
        }
        SidekitText("Central", Modifier.offset(30.dp, 25.dp), style = SidekitTheme.typography.small)
        SidekitText("Belmore Park", Modifier.offset(28.dp, 85.dp), style = SidekitTheme.typography.tiny, color = colors.surfaceA6)
        SidekitText("You", Modifier.offset(250.dp, 210.dp), style = SidekitTheme.typography.tiny)
    }
}

@SidephonePreview
@Composable
private fun MapsPreview() = SidekitPreviewFrame { MapsScreen(MapsSampleData.destination) }
