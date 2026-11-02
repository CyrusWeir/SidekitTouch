package hrln.interactive.sidekit.design.action

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    internal val top: ChromePublisher? get() = publishers.lastOrNull()

    /** Actions to show: the latest override, else the latest screen's. */
    internal val actions: List<SideAction>
        get() = (overrides.lastOrNull() ?: publishers.lastOrNull())?.actions.orEmpty()

    /**
     * Set by the action bar host. Publishers call it from their apply-phase effects so the bar
     * reconciles in the same frame a screen changes, instead of on the host's next recomposition.
     */
    internal var onChange: (() -> Unit)? = null

    /**
     * False for the moment between one screen leaving and the next attaching. Hosts hold their
     * state through it rather than animating towards "nothing".
     */
    internal val hasOwner: Boolean get() = publishers.isNotEmpty() || overrides.isNotEmpty()
}

internal class ChromePublisher {
    // Referential: a new list from a recomposed screen must reach the host so its click handlers
    // are fresh. The host itself only animates when visible content differs.
    var actions: List<SideAction> by mutableStateOf(emptyList(), referentialEqualityPolicy())
    var header: HeaderSpec? by mutableStateOf(null, referentialEqualityPolicy())
}

val LocalChrome = staticCompositionLocalOf<ChromeState?> { null }

/**
 * Publishes this screen's title and actions while it is composed.
 *
 * Last attached wins, so the screen that most recently appeared owns the chrome. A screen that is
 * still composed while leaving keeps updating its own entry without taking the chrome back.
 */
@Composable
fun PublishChrome(header: HeaderSpec?, actions: List<SideAction>) {
    val chrome = LocalChrome.current ?: return
    Publish(chrome, chrome.publishers) {
        it.header = header
        it.actions = actions
    }
}

/** Shows [actions] in place of the screen's while composed (used by T9 typing). */
@Composable
fun PublishActionOverride(actions: List<SideAction>) {
    val chrome = LocalChrome.current ?: return
    Publish(chrome, chrome.overrides) { it.actions = actions }
}

@Composable
private fun Publish(chrome: ChromeState, into: MutableList<ChromePublisher>, update: (ChromePublisher) -> Unit) {
    val publisher = remember { ChromePublisher() }
    DisposableEffect(chrome, publisher) {
        // Filled before it's attached: an attached publisher with no title or actions yet would
        // briefly read as "this screen has an empty bar" and start the wrong animation.
        update(publisher)
        into.add(publisher)
        chrome.onChange?.invoke()
        onDispose {
            into.remove(publisher)
            chrome.onChange?.invoke()
        }
    }
    SideEffect {
        update(publisher)
        chrome.onChange?.invoke()
    }
}
