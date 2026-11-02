package hrln.interactive.sidekit.apps.compass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview
import kotlin.math.cos
import kotlin.math.sin

/** A compass heading: [degrees] clockwise from north, e.g. 0 / "0° N" / "Facing north". */
data class CompassReading(val degrees: Float, val label: String, val description: String)

object CompassSampleData {
    // Placeholder content from "Compass / Home" (96:629).
    val reading = CompassReading(0f, "0° N", "Facing north")
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.compassGraph(nav: NavHostController) {
    composable(SubApp.Compass.route) { LiveCompass { nav.navigate("${SubApp.Compass.route}/bookmarks") } }
    composable("${SubApp.Compass.route}/bookmarks") { CompassBookmarks { nav.popBackStack() } }
}

/** "Compass / Home" (96:629). "← Calibrate" and "Save →" have no mocked destination. */
@Composable
fun CompassScreen(reading: CompassReading, onCalibrate: () -> Unit = {}, onSave: () -> Unit = {}) {
    SidekitScaffold(
        title = "Compass",
        actions = dualArrowActions("Calibrate", "Save", onCalibrate, onSave),
    ) { padding ->
        EverydayContent(padding) {
            // "Compass dial" (96:682): 460 x 278, the 268 dial inset 5 from the top.
            Box(Modifier.fillMaxWidth().height(278.dp), contentAlignment = Alignment.TopCenter) {
                CompassDial({ reading.degrees }, Modifier.padding(top = 5.dp))
            }
            // "Bearing" (96:693).
            SidekitTile(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SidekitText(reading.label, style = SidekitTheme.typography.title)
                    SidekitText(reading.description, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
                }
            }
        }
    }
}

private val DialSize = 268.dp

/** Dial-local centres of the N, E, S, W labels, measured from the mock. */
private val BearingLabels = listOf("N" to Offset(133.5f, 41.5f), "E" to Offset(239.5f, 134.5f), "S" to Offset(133f, 233.5f), "W" to Offset(29.5f, 134.5f))

/** "Vector compass needle and bearings" (96:683), redrawn from its geometry. */
@Composable
internal fun CompassDial(degrees: () -> Float, modifier: Modifier = Modifier) {
    val colors = SidekitTheme.colors
    Box(modifier.size(DialSize).semantics { contentDescription = "Compass dial" }) {
        Canvas(Modifier.fillMaxSize()) {
            val u = 1.dp.toPx()
            val c = Offset(134 * u, 134 * u)
            drawCircle(colors.surfaceA3, radius = 111 * u, center = c, style = Stroke(width = 2 * u))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                drawLine(colors.surfaceA6, c + dir * (104 * u), c + dir * (122 * u), strokeWidth = 3 * u)
            }
            rotate(-degrees(), pivot = c) {
                val north = Path().apply {
                    moveTo(134 * u, 53 * u); lineTo(153 * u, 134 * u); lineTo(115 * u, 134 * u); close()
                }
                val south = Path().apply {
                    moveTo(115 * u, 134 * u); lineTo(153 * u, 134 * u); lineTo(134 * u, 215 * u); close()
                }
                drawPath(north, colors.primaryA1)
                drawPath(south, colors.surfaceA6)
            }
            drawCircle(colors.light, radius = 7 * u, center = c)
        }
        BearingLabels.forEach { (letter, centre) ->
            Box(
                Modifier
                    .offset((centre.x - 20).dp, (centre.y - 15).dp)
                    .size(40.dp, 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                SidekitText(letter, style = SidekitTheme.typography.small)
            }
        }
    }
}

@SidephonePreview
@Composable
private fun CompassPreview() = SidekitPreviewFrame { CompassScreen(CompassSampleData.reading) }
