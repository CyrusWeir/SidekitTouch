package hrln.interactive.sidekit.design.action

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitHeader
import hrln.interactive.sidekit.design.motion.SidekitMotion
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
private class HeaderModel {
    var current: HeaderSpec? by mutableStateOf(null, referentialEqualityPolicy())
    var previous: HeaderSpec? by mutableStateOf(null, referentialEqualityPolicy())

    /** Swap progress; reset synchronously (see ActionSlot.contentProgress). */
    var progress by mutableFloatStateOf(1f)
        private set
    private var job: Job? = null
    private var owner: ChromePublisher? = null

    fun sync(top: ChromePublisher?, spec: HeaderSpec?, scope: CoroutineScope) {
        val old = current
        val newScreen = top !== owner
        owner = top
        current = spec
        // A screen without the shared title draws its own header in that place (the launcher's
        // clock): the old title goes at once, as fading it there would draw it over that header.
        if (spec == null) {
            job?.cancel()
            previous = null
            progress = 1f
            return
        }
        // A screen updating its own title (the clock ticking, a note saved) changes in place; only a
        // new screen plays the swap.
        if (newScreen && old?.title != spec?.title && (old != null || spec != null)) {
            previous = old
            progress = 0f
            job?.cancel()
            job = scope.launch {
                animate(0f, 1f, animationSpec = SidekitMotion.title) { v, _ -> progress = v }
                previous = null
            }
        }
    }
}

/**
 * The shared screen title. On navigation the old title fades out and the new one fades in, both
 * in the same fixed place: the title is the screen's anchor, so it never moves. Both are single
 * text layers faded in the draw phase, measured once.
 */
@Composable
internal fun HeaderHost(state: ChromeState, modifier: Modifier = Modifier) {
    val model = remember { HeaderModel() }
    val scope = rememberCoroutineScope()
    // Read here only to subscribe: HeaderHost recomposes whenever the top screen or its title changes.
    state.top?.header
    // Read at apply time, not from this composition: a screen attaching in the same frame has
    // already synced through onHeaderChange, and syncing back to the stale `top` would undo it.
    SideEffect { state.top?.let { model.sync(it, it.header, scope) } }
    // Publishers notify in the frame a screen changes, so the title never trails the body by a frame.
    DisposableEffect(state, model) {
        state.onHeaderChange = { state.top?.let { model.sync(it, it.header, scope) } }
        onDispose { state.onHeaderChange = null }
    }

    val travel = with(LocalDensity.current) { TITLE_TRAVEL.toPx() }
    val current = model.current
    val previous = model.previous
    // As in the action bar: draw what is composed at rest until composition catches up with a swap
    // that has already reset the (draw-phase) progress.
    val progress = { if (model.current !== current || model.previous !== previous) 1f else model.progress }
    Box(
        modifier
            .fillMaxWidth()
            .height(SidekitTheme.spacing.headerHeight)
            .then(current?.modifier ?: Modifier)
    ) {
        previous?.let { old ->
            SidekitHeader(
                title = old.title,
                textAlign = old.align,
                modifier = Modifier.graphicsLayer {
                    val p = progress()
                    // Out first, then in: two titles never share the slot (overlapping text reads as garbage).
                    alpha = (1f - p / HANDOFF).coerceIn(0f, 1f)
                    translationY = -p * travel
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            )
        }
        if (current != null) {
            SidekitHeader(
                title = current.title,
                textAlign = current.align,
                modifier = Modifier.graphicsLayer {
                    val p = progress()
                    // Composition hasn't caught up with a screen that has no shared title: hide it now.
                    alpha = if (model.current == null) 0f else ((p - HANDOFF) / (1f - HANDOFF)).coerceIn(0f, 1f)
                    translationY = (1f - p) * travel
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            )
        }
    }
}

/** How far the titles slide during a swap: none, so the title keeps a fixed position. */
private val TITLE_TRAVEL = 0.dp

/** Fraction of the swap spent fading the old title out before the new one starts fading in. */
private const val HANDOFF = 0.35f
