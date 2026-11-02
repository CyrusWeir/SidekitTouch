package hrln.interactive.sidekit.design.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.motion.SidekitMotion
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

/** Keeps at most one row of a list swiped open, like iOS. */
@Stable
class SwipeRevealGroup {
    /** Key of the row currently open or being dragged, or null. */
    var openKey: Any? by mutableStateOf(null)
        internal set

    fun closeAll() {
        openKey = null
    }
}

@Composable
fun rememberSwipeRevealGroup(): SwipeRevealGroup = remember { SwipeRevealGroup() }

private class SwipeMetrics {
    var width = 0
}

/**
 * A list row that swipes left to reveal a delete button (iOS style).
 *
 * - Swipe past half the button and release to leave it open; tap it to delete.
 * - Keep dragging past [FULL_SWIPE_FRACTION] of the row and the button stretches to fill it
 *   (with a haptic tick); release there to delete without the extra tap.
 * - Tapping the row while open closes it instead of opening the item.
 * - Opening one row closes any other row in [group].
 *
 * Cost per drag frame: one float state write, a RenderNode translation for the row (no
 * re-record of its contents) and a redraw of the button behind it. Nothing recomposes except
 * when the row crosses open/closed or armed/unarmed.
 */
@Composable
fun SidekitSwipeRow(
    key: Any,
    group: SwipeRevealGroup,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deleteLabel: String = "Delete",
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val colors = SidekitTheme.colors
    val spacing = SidekitTheme.spacing
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val buttonPx = with(density) { spacing.itemHeight.toPx() }
    val gapPx = with(density) { spacing.itemOuterGap.toPx() }
    val radiusPx = with(density) { 36.dp.toPx() }
    val iconPx = with(density) { spacing.iconSize.toPx() }
    val openOffset = -(buttonPx + gapPx)

    val metrics = remember { SwipeMetrics() }
    var offset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val arm = remember { Animatable(0f) }

    val isOpen by remember { derivedStateOf { offset < -1f } }
    val armed by remember { derivedStateOf { metrics.width > 0 && -offset > metrics.width * FULL_SWIPE_FRACTION } }

    fun settle(target: Float, velocity: Float = 0f, then: (() -> Unit)? = null) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offset, target, velocity, SidekitMotion.settle) { value, _ -> offset = value }
            then?.invoke()
        }
    }

    fun delete() {
        group.closeAll()
        onDelete()
    }

    LaunchedEffect(armed) {
        if (armed && dragging) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        arm.animateTo(if (armed) 1f else 0f, SidekitMotion.settle)
    }
    LaunchedEffect(isOpen) {
        if (isOpen) group.openKey = key else if (group.openKey == key && !dragging) group.openKey = null
    }
    LaunchedEffect(group, key) {
        snapshotFlow { group.openKey }.collect { open -> if (open != key && !dragging && offset != 0f) settle(0f) }
    }

    val dragState = rememberDraggableState { delta ->
        val next = offset + delta
        val width = metrics.width.toFloat()
        // Rubber-band past either end.
        offset = when {
            next > 0f -> (offset + delta * RESISTANCE).coerceAtMost(gapPx * 8)
            width > 0f && next < -width -> offset + delta * RESISTANCE
            else -> next
        }
    }

    val trash = rememberVectorPainter(ImageVector.vectorResource(SidekitIcon.DeleteForever.drawable))
    val tint = remember(colors.light) { ColorFilter.tint(colors.light) }

    Box(
        modifier
            .onSizeChanged { metrics.width = it.width }
            .drawBehind {
                val revealed = -offset - gapPx
                if (revealed <= 0f) return@drawBehind
                val a = arm.value
                val left = size.width - revealed
                val r = min(radiusPx, min(revealed, size.height) / 2f)
                drawRoundRect(
                    color = lerp(colors.dangerA1, colors.dangerA2, a),
                    topLeft = Offset(left, 0f),
                    size = Size(revealed, size.height),
                    cornerRadius = CornerRadius(r, r),
                )
                // Icon sits centred in the button, then jumps to the leading edge once armed.
                val cx = if (revealed <= buttonPx) left + revealed / 2f
                else lerp(size.width - buttonPx / 2f, left + buttonPx / 2f, a)
                val iconAlpha = ((revealed - iconPx * 0.5f) / iconPx).coerceIn(0f, 1f)
                val s = iconPx * lerp(0.6f, 1f, iconAlpha) * (1f + 0.15f * a)
                translate(cx - s / 2f, (size.height - s) / 2f) {
                    with(trash) { draw(Size(s, s), alpha = iconAlpha, colorFilter = tint) }
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    settleJob?.cancel()
                    dragging = true
                    group.openKey = key
                },
                onDragStopped = { velocity ->
                    dragging = false
                    val width = metrics.width.toFloat()
                    when {
                        armed -> settle(-width, velocity) { delete() }
                        velocity < -FLING_VELOCITY || (offset < openOffset / 2f && velocity < FLING_VELOCITY) ->
                            settle(openOffset, velocity)
                        else -> {
                            settle(0f, velocity)
                            if (group.openKey == key) group.closeAll()
                        }
                    }
                },
            )
            .semantics {
                customActions = listOf(CustomAccessibilityAction(deleteLabel) { delete(); true })
            },
    ) {
        Box(Modifier.graphicsLayer { translationX = offset }) { content() }
        if (isOpen) {
            // While open, a tap on the row closes it rather than opening the item.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { translationX = offset }
                    .pointerInput(Unit) { detectTapGestures { settle(0f); group.closeAll() } },
            )
            // matchParentSize: rows sit in a lazy list (unbounded height), so the tap target takes
            // the row's measured height rather than fillMaxHeight's (which would be 0).
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .width(spacing.itemHeight)
                        .fillMaxHeight()
                        .clickable(role = Role.Button, onClickLabel = deleteLabel, indication = null, interactionSource = null) {
                            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            delete()
                        },
                )
            }
        }
    }
}

/** Fraction of the row width past which releasing deletes outright. */
const val FULL_SWIPE_FRACTION = 0.6f
private const val RESISTANCE = 0.3f
private const val FLING_VELOCITY = 900f
