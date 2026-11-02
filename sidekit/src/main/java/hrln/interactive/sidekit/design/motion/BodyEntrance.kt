package hrln.interactive.sidekit.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The entrance of a screen's body. The title and action bar are shared and morph; the body is
 * the only part that is new, so it is the only part that "arrives": list rows rise into place one
 * after another, top first.
 *
 * [progress] is read only in graphics layers (draw phase), so the entrance recomposes nothing.
 */
@Stable
class BodyEntrance(arrived: Boolean = false) {
    val progress = Animatable(if (arrived) 1f else 0f)

    /** Set by a list in the body that staggers its own rows; the body then doesn't fade as a whole. */
    var listDriven = false

    suspend fun play() = progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))

    /**
     * Progress of the row in viewport [slot] (0 = top row): rows start [STAGGER] apart and each takes
     * the remaining time, eased.
     */
    fun row(slot: Float): Float {
        val t = progress.value
        if (t >= 1f) return 1f
        val start = (slot.coerceIn(0f, MAX_SLOTS) * STAGGER)
        val local = ((t - start) / (1f - STAGGER * MAX_SLOTS)).coerceIn(0f, 1f)
        return FastOutSlowInEasing.transform(local)
    }

    companion object {
        const val DURATION_MS = 420
        const val STAGGER = 0.07f
        private const val MAX_SLOTS = 5f

        /** Distance rows rise from, in dp. */
        const val RISE_DP = 28f
    }
}

val LocalBodyEntrance = staticCompositionLocalOf<BodyEntrance?> { null }
