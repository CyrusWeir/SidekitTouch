package hrln.interactive.sidekit.design.canvas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionBarHost
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.action.HeaderHost
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** The Sidephone display, and the size of every frame in the SideKit Figma file. */
object SidephoneCanvas {
    const val WIDTH = 480
    const val HEIGHT = 640

    /**
     * Density that fits the 480 x 640 design inside [widthPx] x [heightPx], so 1.dp in SideKit code
     * is exactly 1 Figma px. On a screen taller than 3:4 the canvas then grows down to fill it.
     */
    fun density(widthPx: Int, heightPx: Int): Float =
        minOf(widthPx / WIDTH.toFloat(), heightPx / HEIGHT.toFloat())
}

/**
 * Lays [content] out on a canvas that fills the whole screen, scaled so the 480 x 640 design fits.
 * On the Sidephone this is exactly 480 x 640 and a 1:1 mapping to physical pixels whatever density
 * the system reports. The canvas is always 480 wide: on a taller screen it grows in height to use
 * the rest of the display, and on a wider one (landscape, a tablet) it stays 640 tall and is
 * centred between dark side bars, since rows and the action bar would only stretch sideways.
 * Font scale is pinned to 1 because sizes follow the design, not the system font setting.
 *
 * The canvas also owns the system chrome every screen shares: the title ([HeaderHost]) and the
 * action bar ([ActionBarHost]), drawn above the screens so they morph between them instead of
 * moving with them. The bar rides on top of the soft keyboard. [keypad] (T9 typing) is provided
 * to text fields here.
 *
 * Overdraw: the activity drops its window background, so the canvas is the only full-screen
 * fill. The dark side bars are only painted when there are side bars.
 */
@Composable
fun SidekitCanvas(
    modifier: Modifier = Modifier,
    chrome: ChromeState = remember { ChromeState() },
    keypad: Keypad? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val scale = SidephoneCanvas.density(constraints.maxWidth, constraints.maxHeight)
        if (constraints.maxWidth - SidephoneCanvas.WIDTH * scale >= 1f) Box(Modifier.fillMaxSize().background(SidekitTheme.colors.dark))
        CompositionLocalProvider(
            LocalDensity provides Density(density = scale, fontScale = 1f),
            LocalChrome provides chrome,
            LocalKeypad provides keypad,
        ) {
            val hasFocus = revealFocusOnKeys()
            val screenFocus = remember { FocusRequester() }
            Box(
                modifier = Modifier
                    // Design width, full height: with this density the width is exactly 480 on any
                    // screen at least 3:4 tall, and the height is 640 or more.
                    .width(SidephoneCanvas.WIDTH.dp)
                    .fillMaxHeight()
                    .onFocusChanged { hasFocus.value = it.hasFocus }
                    // Screens slide during navigation; keep them inside the panel.
                    .clipToBounds()
                    .background(SidekitTheme.colors.surfaceA1),
            ) {
                // Screens run under the action bar, so D-pad search never finds them above it and
                // Up from the bar went nowhere. The bar sends Up into this group instead: back to
                // the item focus left from (lists restore their row too), or the bottom item.
                Box(Modifier.fillMaxSize().focusRequester(screenFocus).focusRestorer().focusGroup()) {
                    content()
                }
                // Back closes the options bar before it goes back a screen.
                BackHandler(enabled = chrome.optionsOpen) { chrome.closeOptions() }
                HeaderHost(
                    state = chrome,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = SidekitTheme.spacing.pagePadding, end = SidekitTheme.spacing.pagePadding, top = SidekitTheme.spacing.pagePadding),
                )
                ActionBarHost(
                    state = chrome,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .focusProperties { up = screenFocus }
                        .onPreviewKeyEvent(chrome::barKey)
                        .imePadding()
                        .padding(SidekitTheme.spacing.pagePadding),
                )
            }
        }
    }
}

/**
 * Android swallows the first D-pad press after a touch: it only takes the window out of touch
 * mode. With nothing focused that press would show nothing, so when touch mode ends this focuses
 * the top item, and the first press already shows where you are. Returns the state the canvas
 * keeps up to date with whether anything inside it is focused.
 */
@Composable
private fun revealFocusOnKeys(): MutableState<Boolean> {
    val hasFocus = remember { mutableStateOf(false) }
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnTouchModeChangeListener { inTouchMode ->
            if (!inTouchMode && !hasFocus.value) focusManager.moveFocus(FocusDirection.Down)
        }
        view.viewTreeObserver.addOnTouchModeChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnTouchModeChangeListener(listener) }
    }
    return hasFocus
}
