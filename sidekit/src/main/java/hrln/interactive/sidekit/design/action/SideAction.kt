package hrln.interactive.sidekit.design.action

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.style.TextAlign
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitColors

/** Colour role of an action. */
enum class ActionTone {
    /** Blue: the screen's main way forward. */
    Primary,

    /** Green: commit (save, send, done). */
    Secondary,

    /** Red: destroy. */
    Destructive,

    /** Grey: cancel, undo, back-out, alternatives. */
    Muted;

    fun color(colors: SidekitColors): Color = when (this) {
        Primary -> colors.primaryA1
        Secondary -> colors.successA1
        Destructive -> colors.dangerA1
        Muted -> colors.surfaceA3
    }
}

/**
 * Which way an action's label travels when it appears. Matches the change the action causes,
 * so the button "points" before the user presses it.
 */
enum class ActionMotion {
    /** Enters from the right (goes deeper). */
    Forward,

    /** Enters from the left (goes back). */
    Back,

    /** Rises from below (changes state on this screen). */
    Up,

    /** Scales in on the spot. */
    None,
}

/**
 * One button in the action bar.
 *
 * When the bar changes (new screen or new state) each new action takes over an existing button
 * rather than replacing it: first the button with the same [key], then the button the user just
 * pressed, then the remaining buttons in order. Only surplus buttons merge away and only missing
 * ones split out. So a full-width button becomes the left half of a dual bar by shrinking, while
 * its label and colour change in place; nothing flies off-screen.
 *
 * @param weight share of the bar's flexible width relative to the other non-square actions.
 * @param square fixed width equal to the bar's height. Square actions are icon-only.
 * @param dense tighter padding and smaller text, for bars of short choices (T9 suggestions).
 * @param art custom content drawn instead of the icon (e.g. album art). [artKey] identifies it,
 *   so the bar knows when the art actually changed.
 */
@Immutable
class SideAction(
    val key: String,
    val label: String? = null,
    val icon: SidekitIcon? = null,
    val iconAtEnd: Boolean = false,
    val tone: ActionTone = ActionTone.Primary,
    val weight: Float = 1f,
    val square: Boolean = false,
    val motion: ActionMotion = ActionMotion.None,
    val contentDescription: String? = null,
    val dense: Boolean = false,
    val artKey: Any? = null,
    val art: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
) {
    init {
        require(label != null || icon != null || art != null) { "SideAction '$key' needs a label, icon or art" }
        require(weight > 0f) { "SideAction '$key' weight must be positive" }
    }

    /** What the user can see; a change here plays the content swap. Click handlers are excluded. */
    internal fun sameContent(other: SideAction): Boolean =
        label == other.label && icon == other.icon && iconAtEnd == other.iconAtEnd &&
            dense == other.dense && (art == null) == (other.art == null) && artKey == other.artKey

    /** Everything the bar draws or animates is the same; only click handlers (and art lambdas for the same [artKey]) may differ. */
    internal fun sameLook(other: SideAction): Boolean =
        sameContent(other) && tone == other.tone && weight == other.weight && square == other.square &&
            motion == other.motion && contentDescription == other.contentDescription

    internal val description: String? get() = contentDescription ?: label

    companion object {
        /** Wide button that navigates deeper: label then a right arrow. */
        fun forward(key: String, label: String, onClick: () -> Unit, icon: SidekitIcon = SidekitIcon.ArrowRightAlt) =
            SideAction(key, label, icon, iconAtEnd = true, motion = ActionMotion.Forward, onClick = onClick)

        /** Wide button that returns: left arrow then label. */
        fun back(key: String, label: String, onClick: () -> Unit) =
            SideAction(key, label, SidekitIcon.ArrowLeftAlt, motion = ActionMotion.Back, onClick = onClick)

        /** Square icon-only button. */
        fun square(
            key: String,
            icon: SidekitIcon,
            contentDescription: String,
            onClick: () -> Unit,
            tone: ActionTone = ActionTone.Primary,
            motion: ActionMotion = ActionMotion.None,
        ) = SideAction(key, icon = icon, tone = tone, square = true, motion = motion, contentDescription = contentDescription, onClick = onClick)
    }
}

/**
 * The shared title of a screen. [modifier] goes on the title area (the launcher uses it for the
 * clock's tap, long-press and pull-down gestures).
 */
@Immutable
class HeaderSpec(
    val title: String,
    val align: TextAlign = TextAlign.Start,
    val modifier: Modifier = Modifier,
)

/**
 * The system chrome every screen shares: one title at the top and one action bar at the bottom,
 * owned by [hrln.interactive.sidekit.design.canvas.SidekitCanvas]. Screens never draw either;
 * they publish what they want with [PublishChrome] and the hosts morph from the previous screen's
 * chrome to the new one. Only the body (the scrollable list) belongs to the screen itself, so a
 * navigation is three separate transitions (title, body, bar) instead of one screen sliding.
 */
@Stable
class ChromeState {
    internal val publishers = mutableStateListOf<ChromePublisher>()

    /** Temporary bars that take over from the screen's (T9 suggestions while typing). */
    internal val overrides = mutableStateListOf<ChromePublisher>()

    /** Key handlers screens registered with [KeysFromActionBar]; the newest one is asked first. */
    internal val barKeys = mutableStateListOf<(KeyEvent) -> Boolean>()

    /** Offers a key pressed while focus is in the action bar to the screen that asked for it. */
    internal fun barKey(event: KeyEvent): Boolean = barKeys.lastOrNull()?.invoke(event) ?: false

    internal val top: ChromePublisher? get() = publishers.lastOrNull()

    /** Whose options are showing in the bar, if any (see [toggleOptions]). */
    private var optionsOwner: ChromePublisher? by mutableStateOf(null)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val autoClose = Runnable { closeOptions() }

    /** The publisher the Options key talks to: the typing override if it has options, else the screen. */
    private val optionsSource: ChromePublisher?
        get() = overrides.lastOrNull()?.takeIf { it.options.isNotEmpty() } ?: top

    /** True while the bar shows the current screen's options instead of its actions. */
    val optionsOpen: Boolean
        get() = optionsOwner.let { it != null && it === optionsSource }

    /**
     * The Options key (the keypad's top-right key): every screen's universal "more" button. Swaps
     * the action bar to the screen's [ChromePublisher.options] (plus a close button) and back. A
     * screen without options briefly says so instead of doing nothing.
     */
    fun toggleOptions() {
        handler.removeCallbacks(autoClose)
        if (optionsOpen) {
            optionsOwner = null
        } else {
            val source = optionsSource ?: return
            optionsOwner = source
            if (source.options.isEmpty()) handler.postDelayed(autoClose, NO_OPTIONS_MS)
        }
        onChange?.invoke()
    }

    fun closeOptions() {
        handler.removeCallbacks(autoClose)
        if (optionsOwner == null) return
        optionsOwner = null
        onChange?.invoke()
    }

    internal fun detached(publisher: ChromePublisher) {
        if (optionsOwner === publisher) optionsOwner = null
    }

    /** Actions to show: open options, else the latest override, else the latest screen's. */
    internal val actions: List<SideAction>
        get() {
            val owner = optionsOwner
            val source = if (owner != null && owner === optionsSource) owner else overrides.lastOrNull() ?: publishers.lastOrNull()
            val base = if (owner != null && source === owner) owner.optionsBar(::closeOptions) else source?.actions.orEmpty()
            val trailing = source?.trailingAction ?: return base
            return base + trailing
        }

    /**
     * Set by the action bar host. Publishers call it from their apply-phase effects so the bar
     * reconciles in the same frame a screen changes, instead of on the host's next recomposition.
     */
    internal var onChange: (() -> Unit)? = null

    /** Set by the title host, for the same reason as [onChange]. */
    internal var onHeaderChange: (() -> Unit)? = null

    internal fun changed() {
        onChange?.invoke()
        onHeaderChange?.invoke()
    }

    /**
     * False for the moment between one screen leaving and the next attaching. Hosts hold their
     * state through it rather than animating towards "nothing".
     */
    internal val hasOwner: Boolean get() = publishers.isNotEmpty() || overrides.isNotEmpty()

    private companion object {
        const val NO_OPTIONS_MS = 1_200L
    }
}

internal class ChromePublisher {
    // Referential: a new list from a recomposed screen must reach the host so its click handlers
    // are fresh. The host itself only animates when visible content differs.
    var actions: List<SideAction> by mutableStateOf(emptyList(), referentialEqualityPolicy())
    var trailingAction: SideAction? by mutableStateOf(null, referentialEqualityPolicy())
    var header: HeaderSpec? by mutableStateOf(null, referentialEqualityPolicy())
    var options: List<SideAction> by mutableStateOf(emptyList(), referentialEqualityPolicy())

    private var bar: List<SideAction>? = null
    private var barFor: List<SideAction>? = null

    /**
     * The options as an action bar: each option closes the bar after running, and a square close
     * button ends it. Cached per options list so the bar host sees a stable list while open.
     */
    fun optionsBar(close: () -> Unit): List<SideAction> {
        val opts = options
        if (barFor === opts) bar?.let { return it }
        val built = if (opts.isEmpty()) {
            listOf(SideAction("options.none", "No options here", tone = ActionTone.Muted, motion = ActionMotion.Up, onClick = close))
        } else {
            opts.map { o ->
                SideAction(
                    "options.${o.key}", o.label, o.icon, o.iconAtEnd, o.tone, o.weight, o.square,
                    ActionMotion.Up, o.contentDescription, dense = o.dense || opts.size > 2, artKey = o.artKey, art = o.art,
                ) {
                    close()
                    o.onClick()
                }
            } + SideAction.square("options.close", SidekitIcon.Close, "Close options", close, tone = ActionTone.Muted)
        }
        bar = built
        barFor = opts
        return built
    }
}

val LocalChrome = staticCompositionLocalOf<ChromeState?> { null }

/**
 * Lets the current screen see key presses while focus is in the action bar. The bar is drawn by
 * the canvas, outside the screen's body, so a key handler on the body never hears them: a screen
 * that takes keypad digits anywhere (the contacts search) registers them here too.
 */
@Composable
fun KeysFromActionBar(onKey: (KeyEvent) -> Boolean) {
    val chrome = LocalChrome.current ?: return
    val latest by rememberUpdatedState(onKey)
    DisposableEffect(chrome) {
        val handler: (KeyEvent) -> Boolean = { latest(it) }
        chrome.barKeys += handler
        onDispose { chrome.barKeys -= handler }
    }
}

/**
 * Publishes this screen's title and actions while it is composed.
 *
 * Last attached wins, so the screen that most recently appeared owns the chrome. A screen that is
 * still composed while leaving keeps updating its own entry without taking the chrome back.
 */
@Composable
fun PublishChrome(header: HeaderSpec?, actions: List<SideAction>, options: List<SideAction> = emptyList(), trailingAction: SideAction? = null) {
    val chrome = LocalChrome.current ?: return
    Publish(chrome, chrome.publishers) {
        it.header = header
        it.actions = actions
        it.options = options
        it.trailingAction = trailingAction
    }
}

/**
 * Shows [actions] in place of the screen's while composed (used by T9 typing). [options], if
 * any, replace the screen's options for the Options key too. [trailingAction] stays at the right
 * edge in both bars.
 */
@Composable
fun PublishActionOverride(actions: List<SideAction>, options: List<SideAction> = emptyList(), trailingAction: SideAction? = null) {
    val chrome = LocalChrome.current ?: return
    Publish(chrome, chrome.overrides) {
        it.actions = actions
        it.options = options
        it.trailingAction = trailingAction
    }
}

@Composable
private fun Publish(chrome: ChromeState, into: MutableList<ChromePublisher>, update: (ChromePublisher) -> Unit) {
    val publisher = remember { ChromePublisher() }
    DisposableEffect(chrome, publisher) {
        // Filled before it's attached: an attached publisher with no title or actions yet would
        // briefly read as "this screen has an empty bar" and start the wrong animation.
        update(publisher)
        into.add(publisher)
        chrome.changed()
        onDispose {
            into.remove(publisher)
            chrome.detached(publisher)
            chrome.changed()
        }
    }
    SideEffect {
        update(publisher)
        chrome.changed()
    }
}
