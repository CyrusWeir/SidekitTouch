package hrln.interactive.sidekit.design.action

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import hrln.interactive.sidekit.design.theme.SidekitIndication
import hrln.interactive.sidekit.design.theme.drawFocusRing
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.theme.contentColorFor
import hrln.interactive.sidekit.design.motion.SidekitMotion
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One rendered button. Survives across action changes for as long as its key does, so its
 * animations can be retargeted rather than restarted.
 */
@Stable
internal class ActionSlot(action: SideAction, present: Boolean) {
    /** The action this button currently shows. Changes when a different action takes it over. */
    var key: String = action.key
    var action: SideAction by mutableStateOf(action, referentialEqualityPolicy())
        private set

    /** Content being swapped out, drawn under [action] until [contentProgress] reaches 1. */
    var previous: SideAction? by mutableStateOf(null)
        private set
    var removing: Boolean by mutableStateOf(false)

    val presence = Animatable(if (present) 1f else 0f)
    val squareness = Animatable(if (action.square) 1f else 0f)
    val weight = Animatable(action.weight)
    /**
     * Label swap progress, 0 (old label) to 1 (new). A plain float state rather than an Animatable
     * so it can be reset to 0 synchronously in [update]: otherwise the new label is drawn at full
     * strength for one frame before the swap begins.
     */
    var contentProgress by mutableFloatStateOf(1f)
        private set
    private var contentJob: Job? = null
    val press = Animatable(0f)

    /** Last settled content width, used to measure content while this slot merges away. */
    var contentMaxWidth: Int = 0

    fun update(next: SideAction, scope: CoroutineScope) {
        val old = action
        action = next
        if (!old.sameContent(next)) {
            previous = old
            contentProgress = 0f
            contentJob?.cancel()
            contentJob = scope.launch {
                animate(0f, 1f, animationSpec = SidekitMotion.content) { v, _ -> contentProgress = v }
                previous = null
            }
        }
        if (old.square != next.square) scope.launch { squareness.animateTo(if (next.square) 1f else 0f, SidekitMotion.morph) }
        if (old.weight != next.weight) scope.launch { weight.animateTo(next.weight, SidekitMotion.morph) }
    }
}

@Stable
internal class ActionBarModel {
    val slots = mutableStateListOf<ActionSlot>()
    val shown = Animatable(0f)
    private var hideJob: Job? = null
    private var synced: List<SideAction>? = null

    /** Key of the button the user last pressed; its action's successor takes this very button. */
    var lastPressed: String? = null

    fun sync(target: List<SideAction>, scope: CoroutineScope) {
        if (target === synced) return
        synced = target
        reconcile(target, scope)
    }

    fun reconcile(target: List<SideAction>, scope: CoroutineScope) {
        if (target.isEmpty()) {
            if (slots.isEmpty() || hideJob?.isActive == true) return
            // Slide the last actions away as they are; morphing them to nothing would read as a bug.
            hideJob = scope.launch {
                shown.animateTo(0f, SidekitMotion.enter)
                slots.clear()
            }
            return
        }

        hideJob?.cancel()
        hideJob = null
        if (slots.isEmpty()) {
            // Arriving from no bar: build the final layout and slide it up whole.
            target.forEach { slots.add(ActionSlot(it, present = true)) }
            scope.launch { shown.animateTo(1f, SidekitMotion.enter) }
            return
        }
        if (shown.targetValue < 1f || shown.value < 1f) scope.launch { shown.animateTo(1f, SidekitMotion.enter) }

        val keys = HashSet<String>(target.size * 2)
        target.forEach { keys.add(it.key) }

        // Buttons free to be taken over by actions with new keys: the one just pressed first (so
        // the button you tapped becomes the next screen's button), then the rest left to right.
        val free = ArrayList<ActionSlot>(slots.size)
        slots.filterTo(free) { it.key !in keys && !it.removing }
        lastPressed?.let { pressed ->
            val i = free.indexOfFirst { it.key == pressed }
            if (i > 0) free.add(0, free.removeAt(i))
        }
        lastPressed = null

        target.forEachIndexed { i, action ->
            var slot = slots.firstOrNull { it.key == action.key }
            if (slot == null && free.isNotEmpty()) {
                slot = free.removeAt(0)
                slot.key = action.key
            }
            if (slot != null) {
                slot.removing = false
                slot.update(action, scope)
                if (slot.presence.targetValue != 1f || slot.presence.value != 1f) {
                    val s = slot
                    scope.launch { s.presence.animateTo(1f, SidekitMotion.morph) }
                }
            } else {
                // More actions than buttons: split a new one out next to its neighbour.
                val created = ActionSlot(action, present = false)
                val prevKey = if (i == 0) null else target[i - 1].key
                val index = if (prevKey == null) 0 else slots.indexOfFirst { it.key == prevKey } + 1
                slots.add(index.coerceIn(0, slots.size), created)
                scope.launch { created.presence.animateTo(1f, SidekitMotion.morph) }
            }
        }

        // Fewer actions than buttons: the surplus merge into their neighbours.
        for (slot in free) {
            slot.removing = true
            scope.launch {
                slot.presence.animateTo(0f, SidekitMotion.morph)
                if (slot.removing) slots.remove(slot)
            }
        }

        // Existing keys can stay in the bar while their target order changes (for example when the
        // launcher dock is reordered). Move those slots to the requested order; removing slots stay
        // where they are and fade out, so they do not block the final positions.
        alignSlotsToTarget(slots, target)
    }
}

/** Places slots carrying target keys in target order, leaving surplus slots to fade out. */
internal fun alignSlotsToTarget(slots: MutableList<ActionSlot>, target: List<SideAction>) {
    target.forEachIndexed { index, action ->
        val current = slots.indexOfFirst { it.key == action.key }
        if (current >= 0 && current != index) slots.add(index, slots.removeAt(current))
    }
}

/**
 * Renders the shared action bar.
 *
 * Performance notes, because this is on screen in every app:
 * - Every animated value (widths, gaps, slide, press squash, label swap) is read in the layout
 *   or draw phase, never in composition. A morph re-runs one measure pass and a few draw calls
 *   per frame; it recomposes nothing.
 * - Labels are measured against the slot's *target* width, which is constant for the whole
 *   morph, so text is shaped once per change rather than every frame while the button grows.
 * - Alpha uses [CompositingStrategy.ModulateAlpha] so fading content never allocates an
 *   offscreen buffer.
 */
@Composable
internal fun ActionBarHost(state: ChromeState, modifier: Modifier = Modifier) {
    val model = remember { ActionBarModel() }
    val scope = rememberCoroutineScope()
    // Publishers notify synchronously (same frame as the screen's recomposition); the SideEffect
    // is a backstop for changes that arrive without a notification. Both are idempotent.
    DisposableEffect(state, model) {
        state.onChange = { if (state.hasOwner) model.sync(state.actions, scope) }
        onDispose { state.onChange = null }
    }
    val target = state.actions
    val owned = state.hasOwner
    SideEffect { if (owned) model.sync(target, scope) }

    if (model.slots.isEmpty()) return

    val spacing = SidekitTheme.spacing
    val gapPx = with(LocalDensity.current) { spacing.actionButtonGap.toPx() }
    // Labels may reach into the button's generous side padding; only this margin is sacred.
    val edgePx = with(LocalDensity.current) { LABEL_EDGE.toPx() }
    val iconPx = with(LocalDensity.current) { spacing.iconSize.roundToPx() }
    val slots = model.slots

    Layout(
        contents = listOf(
            { slots.forEach { slot -> key(slot) { SlotSurface(slot, model) } } },
            { slots.forEach { slot -> key(slot) { SlotContent(slot) } } },
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(spacing.actionBarHeight)
            .graphicsLayer {
                // Slide only: no alpha, so no offscreen layer for the whole bar.
                translationY = (1f - model.shown.value) * (size.height + spacing.pagePadding.toPx())
            },
    ) { (surfaces, contents), constraints ->
        val n = min(slots.size, min(surfaces.size, contents.size))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val widths = FloatArray(n)
        val gaps = FloatArray(n)
        val targets = FloatArray(n)
        layoutSlots(slots, n, width, height, gapPx, widths, gaps, settled = false)
        layoutSlots(slots, n, width, height, gapPx, targets, null, settled = true)

        val surfacePlaceables = Array(n) { i ->
            val w = widths[i].roundToInt().coerceAtLeast(0)
            surfaces[i].measure(Constraints.fixed(w, height))
        }
        val contentPlaceables = Array(n) { i ->
            val slot = slots[i]
            if (!slot.removing) slot.contentMaxWidth = (targets[i] - 2 * edgePx).roundToInt().coerceAtLeast(iconPx)
            // Sized to the slot so its layer can clip to it. The labels inside are measured against
            // contentMaxWidth (see fitToSlot), not this width, so they are not re-shaped per frame.
            contents[i].measure(Constraints.fixed(widths[i].roundToInt().coerceAtLeast(0), height))
        }

        layout(width, height) {
            var x = 0f
            for (i in 0 until n) {
                x += gaps[i]
                val slot = slots[i]
                val left = x.roundToInt()
                surfacePlaceables[i].place(left, 0)
                contentPlaceables[i].placeWithLayer(left, 0) {
                    val s = 1f - PRESS_SCALE * slot.press.value
                    scaleX = s
                    scaleY = s
                }
                x += widths[i]
            }
        }
    }
}

private const val PRESS_SCALE = 0.04f

/**
 * Splits [width] between [n] slots. Square slots demand `height * presence`; flexible slots share
 * what's left by `weight * presence`. Squareness blends the two so a button can turn from wide to
 * square continuously.
 *
 * The gap before slot i is `gap * min(p_i, max(p_0..p_{i-1}))`: a slot appearing at either end or
 * in the middle brings exactly one gap with it, and one merging away takes exactly one with it.
 */
private fun layoutSlots(
    slots: List<ActionSlot>,
    n: Int,
    width: Int,
    height: Int,
    gapPx: Float,
    outWidths: FloatArray,
    outGaps: FloatArray?,
    settled: Boolean,
) {
    var gapTotal = 0f
    var fixedTotal = 0f
    var flexTotal = 0f
    var maxBefore = 0f
    // First pass stores each slot's flexible demand in outWidths; second pass resolves widths.
    val fixed = FloatArray(n)
    for (i in 0 until n) {
        val slot = slots[i]
        val presence: Float
        val squareness: Float
        val weight: Float
        if (settled) {
            presence = if (slot.removing) 0f else 1f
            squareness = if (slot.action.square) 1f else 0f
            weight = slot.action.weight
        } else {
            presence = slot.presence.value.coerceIn(0f, 1f)
            squareness = slot.squareness.value.coerceIn(0f, 1f)
            weight = slot.weight.value.coerceAtLeast(0f)
        }
        val gap = if (i == 0) 0f else gapPx * min(presence, maxBefore)
        outGaps?.set(i, gap)
        gapTotal += gap
        maxBefore = max(maxBefore, presence)
        fixed[i] = height * presence * squareness
        fixedTotal += fixed[i]
        outWidths[i] = weight * presence * (1f - squareness)
        flexTotal += outWidths[i]
    }
    val remaining = (width - gapTotal - fixedTotal).coerceAtLeast(0f)
    for (i in 0 until n) {
        outWidths[i] = fixed[i] + if (flexTotal > 0f) remaining * outWidths[i] / flexTotal else 0f
    }
}

@Composable
private fun SlotSurface(slot: ActionSlot, model: ActionBarModel) {
    val colors = SidekitTheme.colors
    val shape = SidekitTheme.shapes.actionButton
    val color by animateColorAsState(slot.action.tone.color(colors), SidekitMotion.tone, label = "actionTone")
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current

    // D-pad focus: the same ring as every other SideKit surface, following the button's shape.
    val focus = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            when (it) {
                is PressInteraction.Press -> launch { slot.press.animateTo(1f, SidekitMotion.press) }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch { slot.press.animateTo(0f, SidekitMotion.press) }
                is FocusInteraction.Focus -> launch { focus.animateTo(1f, tween(SidekitIndication.FOCUS_MS)) }
                is FocusInteraction.Unfocus -> launch { focus.animateTo(0f, tween(SidekitIndication.FOCUS_MS)) }
            }
        }
    }

    val action = slot.action
    Box(
        Modifier
            .graphicsLayer {
                val s = 1f - PRESS_SCALE * slot.press.value
                scaleX = s
                scaleY = s
            }
            .drawWithCache {
                // Rebuilt only when the size changes; the colour is read at draw time.
                val outline = shape.createOutline(size, layoutDirection, this)
                val ringRadius = ACTION_RADIUS.toPx()
                onDrawWithContent {
                    drawOutline(outline, color)
                    drawContent()
                    val f = focus.value
                    if (f > 0f) drawFocusRing(f, cornerRadius = ringRadius)
                }
            }
            .semantics { action.description?.let { contentDescription = it } }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !slot.removing,
                role = Role.Button,
                onClickLabel = action.description,
            ) {
                view.performHapticFeedback(
                    when (slot.action.tone) {
                        ActionTone.Secondary -> HapticFeedbackConstants.CONFIRM
                        ActionTone.Destructive -> HapticFeedbackConstants.REJECT
                        else -> HapticFeedbackConstants.VIRTUAL_KEY
                    },
                )
                model.lastPressed = slot.key
                slot.action.onClick()
            },
    )
}

@Composable
private fun SlotContent(slot: ActionSlot) {
    val distance = with(LocalDensity.current) { CONTENT_TRAVEL.toPx() }
    // Labels slide in and out of the button's edges rather than over its neighbours. A canvas
    // clipRect: cheaper than an outline clip and honoured by every renderer.
    Box(Modifier.drawWithContent { clipRect { this@drawWithContent.drawContent() } }, contentAlignment = Alignment.Center) {
        val previous = slot.previous
        val current = slot.action
        // Progress is read at draw time and changes immediately; these labels only change when this
        // recomposes. Until then, draw what is composed at rest rather than mid-swap.
        val stale = { slot.action !== current || slot.previous !== previous }
        if (previous != null) {
            ActionContent(
                previous,
                // The outgoing label keeps its natural width (measured once, unbounded) instead of
                // being re-ellipsized to fit the slot it's leaving.
                Modifier.wrapContentWidth(unbounded = true).graphicsLayer {
                    val p = if (stale()) 1f else slot.contentProgress
                    alpha = (1f - p / LABEL_HANDOFF).coerceIn(0f, 1f)
                    applyTravel(current.motion, -p, distance)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            )
        }
        ActionContent(current, Modifier.fitToSlot(slot, distance, current, previous != null, stale))
    }
}

/**
 * Measures the label against the slot's settled width ([ActionSlot.contentMaxWidth], constant for
 * a whole morph, so the text is shaped once) and fades it with the room it actually has, so a
 * button that is still growing or already shrinking never shows a squashed label.
 */
private fun Modifier.fitToSlot(
    slot: ActionSlot,
    distance: Float,
    shown: SideAction,
    hasPrevious: Boolean,
    stale: () -> Boolean,
) = layout { measurable, constraints ->
    // A stale label (see SlotContent) belongs to the old layout: keep its natural width rather than
    // ellipsizing it to the new one for a frame.
    val maxWidth = if (stale()) Constraints.Infinity else slot.contentMaxWidth.coerceAtLeast(0)
    val placeable = measurable.measure(Constraints(maxWidth = maxWidth, maxHeight = constraints.maxHeight))
    val w = constraints.maxWidth
    layout(w, placeable.height) {
        placeable.placeWithLayer((w - placeable.width) / 2, 0) {
            val p = if (stale()) 1f else slot.contentProgress
            val cw = placeable.width
            // 0 until the label fits inside the button, 1 once it clears the edge margin.
            // Measured against the margin rather than the full padding, since labels may extend
            // into the padding and would otherwise stay permanently faded.
            val fit = ((w - cw) / (2f * LABEL_EDGE.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
            // The incoming label waits for the outgoing one to clear (see LABEL_HANDOFF).
            val incoming = if (!hasPrevious) p else (p - LABEL_HANDOFF) / (1f - LABEL_HANDOFF)
            alpha = incoming.coerceIn(0f, 1f) * fit
            applyTravel(shown.motion, 1f - p, distance)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }
}

private val CONTENT_TRAVEL = 56.dp

/** Smallest gap kept between a label and the button's outer edge. */
private val LABEL_EDGE = 8.dp

/** Fraction of a label swap spent fading the old label before the new one appears. */
private const val LABEL_HANDOFF = 0.3f

/** [t] is 1 at the entry position and 0 at rest; negative for the exit side. */
private fun GraphicsLayerScope.applyTravel(motion: ActionMotion, t: Float, distance: Float) {
    when (motion) {
        ActionMotion.Forward -> translationX = t * distance
        ActionMotion.Back -> translationX = -t * distance
        ActionMotion.Up -> translationY = t * distance * 0.7f
        ActionMotion.None -> {
            val s = 1f - 0.25f * abs(t)
            scaleX = s
            scaleY = s
        }
    }
}

/**
 * An action label that wraps onto a second row instead of truncating. It is measured at [oneLine]
 * first; only a label that cannot fit on one line falls back to [twoLine] (a step down the type
 * scale) and is allowed to wrap to two lines. Labels too long even for two are ellipsized, so a
 * button never grows a third row.
 *
 * Measurement happens in the layout phase against the slot's settled width, which is constant for
 * a whole morph, so text is shaped once per content change rather than every frame. The result is
 * drawn through a shared [rememberTextMeasurer] cache.
 */
@Composable
private fun ActionLabel(
    text: String,
    oneLine: TextStyle,
    twoLine: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val singleStyle = remember(oneLine, color) { oneLine.copy(color = color, textAlign = TextAlign.Center) }
    val wrappedStyle = remember(twoLine, color) { twoLine.copy(color = color, textAlign = TextAlign.Center) }
    var drawn by remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        content = {},
        modifier = modifier.drawWithContent {
            drawContent()
            drawn?.let { drawText(it) }
        },
    ) { _, constraints ->
        val bounds = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val single = measurer.measure(text, singleStyle, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = bounds)
        // lineCount == 1 means it fits on one row at the normal size, so it is never shrunk.
        val result = if (single.lineCount <= 1) single else {
            measurer.measure(text, wrappedStyle, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = bounds)
        }
        drawn = result
        layout(result.size.width, result.size.height) {}
    }
}

@Composable
private fun ActionContent(action: SideAction, modifier: Modifier) {
    val spacing = SidekitTheme.spacing
    val foreground = SidekitTheme.colors.contentColorFor(action.tone.color(SidekitTheme.colors))
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        // Action buttons keep their icons closer to the label than list rows do. With long
        // single-line labels the row fills the slot, and a list-sized gap makes the text look
        // detached from its icon.
        horizontalArrangement = Arrangement.spacedBy(
            if (action.dense) spacing.itemContentGap / 2 - 2.dp else spacing.itemContentGap - 4.dp,
        ),
    ) {
        val icon = action.icon
        val art = action.art
        if (art != null) art()
        if (icon != null && !action.iconAtEnd) SidekitIconImage(icon, tint = foreground)
        val label = action.label
        if (label != null) {
            val typography = SidekitTheme.typography
            ActionLabel(
                text = label,
                oneLine = if (action.dense) typography.label else typography.body,
                twoLine = if (action.dense) typography.caption else typography.label,
                color = foreground,
                // Weighted so the icons are measured first and keep their space: otherwise a
                // full-width two-line label squeezes a trailing arrow down to nothing.
                modifier = Modifier
                    .weight(1f, fill = false)
                    // Drawn text does not expose semantics on its own. Always expose the label as
                    // text so tests and accessibility can find the button by what it says, even
                    // when the action also has a content description.
                    .semantics { text = AnnotatedString(label) },
            )
        }
        if (icon != null && action.iconAtEnd) SidekitIconImage(icon, tint = foreground)
    }
}

/** Corner radius of [hrln.interactive.sidekit.design.theme.SidekitShapes.actionButton], for its focus ring. */
private val ACTION_RADIUS = 48.dp
