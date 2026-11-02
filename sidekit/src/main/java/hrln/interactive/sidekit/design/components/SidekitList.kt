package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    return graphicsLayer {
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
        alpha = a
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Vertical SideKit list: rows separated by `item-outer-gap`, with content padding from the scaffold. */
@Composable
fun SidekitList(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    // Rows stagger their own entrance (see listFade), so the body shouldn't also fade as a block.
    LocalBodyEntrance.current?.listDriven = true
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap),
        content = content,
    )
}
