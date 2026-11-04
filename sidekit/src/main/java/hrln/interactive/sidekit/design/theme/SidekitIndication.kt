package hrln.interactive.sidekit.design.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Feedback for every SideKit clickable:
 *
 * - Pressed: a light veil over the surface (Figma has no pressed state, so this is minimal).
 * - Focused (D-pad / keypad navigation): a bright ring inside the edge and a soft lift, so it is
 *   obvious at arm's length which item OK will open. Compose only focuses clickables when keys are
 *   in use, so touch never shows it.
 *
 * Surfaces clip to their shape before they are clickable, so the veil follows the shape; the ring
 * is drawn as a rounded rectangle with the item radius, inset to stay inside that clip.
 */
internal object SidekitIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = IndicationNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0

    private class IndicationNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false
        private val focus = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                        is FocusInteraction.Focus -> launch { focus.animateTo(1f, tween(FOCUS_MS)) { invalidateDraw() } }
                        is FocusInteraction.Unfocus -> launch { focus.animateTo(0f, tween(FOCUS_MS)) { invalidateDraw() } }
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val f = focus.value
            if (f > 0f) drawFocusRing(f)
            if (pressed) drawRect(Color.White.copy(alpha = 0.12f))
        }
    }

    const val FOCUS_MS = 120
}

/** The focus ring and lift, at [progress] (0..1). Shared with surfaces that draw their own feedback. */
fun DrawScope.drawFocusRing(progress: Float, cornerRadius: Float = SidekitFocus.cornerRadius.toPx()) {
    val outer = minOf(cornerRadius, size.minDimension / 2)
    // Rounded, not a plain rect: surfaces that don't clip (action bar buttons) would show corners.
    drawRoundRect(Color.White.copy(alpha = SidekitFocus.LIFT_ALPHA * progress), cornerRadius = CornerRadius(outer))
    val width = SidekitFocus.ringWidth.toPx()
    val inset = width / 2
    val radius = (outer - inset).coerceAtLeast(0f)
    drawRoundRect(
        color = SidekitFocus.ring.copy(alpha = progress),
        topLeft = Offset(inset, inset),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(radius),
        style = Stroke(width),
    )
}

/** Focus ring tokens. */
object SidekitFocus {
    val ring = Color.White
    val ringWidth = 5.dp
    val cornerRadius = 36.dp
    const val LIFT_ALPHA = 0.10f
}
