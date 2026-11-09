package hrln.interactive.sidekit.design.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color

/**
 * Shared motion specs. Specs are allocated once here and reused everywhere so animation code
 * never allocates a spec per recomposition or per gesture.
 *
 * Springs rather than tweens for anything the user's finger hands off to: a spring takes the
 * fling velocity and can be retargeted mid-flight without a visible seam, which matters when
 * the action bar changes again before its last morph has settled.
 */
object SidekitMotion {
    /** Action bar split / merge: widths and gaps. Slightly soft so the split reads as liquid. */
    val morph = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)

    /** Label and icon swap inside a button. Under-damped so arrows overshoot toward their direction. */
    val content = spring<Float>(dampingRatio = 0.68f, stiffness = 520f)

    /** Screen title swap. Critically damped: the title rises into its fixed place and stops there. */
    val title = spring<Float>(dampingRatio = 1f, stiffness = 520f)

    /** Bar sliding in from / out to the bottom edge when a screen gains or loses actions. */
    val enter = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

    /** Press squash. Stiff so it tracks the finger. */
    val press = spring<Float>(dampingRatio = 0.6f, stiffness = 1400f)

    /** Swipe-to-reveal settle. */
    val settle = spring<Float>(dampingRatio = 0.86f, stiffness = 700f)

    val tone = spring<Color>(dampingRatio = 1f, stiffness = 500f)
}
