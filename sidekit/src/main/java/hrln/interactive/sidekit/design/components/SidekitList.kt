package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.motion.BodyEntrance
import hrln.interactive.sidekit.design.motion.LocalBodyEntrance
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * The SideKit list fade. In every list mock each successive row steps down in opacity
 * (1.0, 0.8, 0.6, 0.4 ...) over the page background, so lower rows recede behind the
 * action bar. Alpha is a function of where the row sits in the viewport, so it updates
 * smoothly while scrolling.
 */
object ListFade {
    const val STEP = 0.2f
    const val MIN_ALPHA = 0.2f

    /** @param offsetPx row top relative to the list viewport. @param pitchPx row height plus gap. */
    fun alpha(offsetPx: Int, pitchPx: Int): Float {
        if (offsetPx <= 0 || pitchPx <= 0) return 1f
        val slot = offsetPx.toFloat() / pitchPx
        return (1f - STEP * slot).coerceIn(MIN_ALPHA, 1f)
    }
}

/**
 * Applies [ListFade] to the item at [index] of the list driven by [state], plus the body's
 * staggered entrance (rows rise into place top first when a screen appears).
 *
 * Runs in the draw phase only (scrolling and the entrance never recompose rows). Rows are faded
 * with [CompositingStrategy.ModulateAlpha]: each draw call is tinted directly instead of rendering
 * the row to an offscreen buffer and blending it, which with ~5 faded rows on screen is the
 * difference between 5 extra full-row GPU passes per frame and none.
 */
@Composable
fun Modifier.listFade(state: LazyListState, index: Int): Modifier {
    val entrance = LocalBodyEntrance.current
    // A row holding D-pad focus is never faded: it is what OK will open.
    val focused = remember { mutableStateOf(false) }
    return onFocusChanged { focused.value = it.hasFocus }.graphicsLayer {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        // Visible items are contiguous and ordered, so look the row up directly instead of scanning.
        val first = visible.firstOrNull()?.index ?: 0
        val item = visible.getOrNull(index - first)?.takeIf { it.index == index }
        var a = 1f
        var slot = 0f
        if (item != null) {
            val pitch = item.size + info.mainAxisItemSpacing
            val offset = item.offset - info.viewportStartOffset
            a = ListFade.alpha(offset, pitch)
            slot = if (pitch > 0) offset.coerceAtLeast(0).toFloat() / pitch else 0f
        }
        if (entrance != null) {
            val p = entrance.row(slot)
            a *= p
            translationY = (1f - p) * BodyEntrance.RISE_DP.dp.toPx()
        }
        alpha = if (focused.value) maxOf(a, entrance?.row(slot) ?: 1f) else a
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Vertical SideKit list: rows separated by `item-outer-gap`, with content padding from the scaffold. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidekitList(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    // Rows stagger their own entrance (see listFade), so the body shouldn't also fade as a block.
    LocalBodyEntrance.current?.listDriven = true
    val density = LocalDensity.current
    val spacing = SidekitTheme.spacing
    val focusScroll = remember(contentPadding, density, spacing) {
        with(density) {
            FocusBand(
                top = contentPadding.calculateTopPadding().toPx(),
                pitch = (spacing.itemHeight + spacing.itemOuterGap).toPx(),
            )
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
        LazyColumn(
            // Coming back to the list (Up from the action bar) lands on the row focus left from.
            modifier = modifier.fillMaxSize().focusRestorer(),
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing.itemOuterGap),
            content = content,
        )
    }
}

/**
 * Where D-pad focus scrolls a list to: the focused row is kept in the top two slots under the
 * title, where the list fade is lightest and the action bar is far away. Moving down a long list
 * scrolls it a row at a time, like a wheel menu.
 */
private class FocusBand(private val top: Float, private val pitch: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val bandEnd = top + pitch
        return when {
            offset < top -> offset - top
            offset > bandEnd -> offset - bandEnd
            else -> 0f
        }
    }
}
