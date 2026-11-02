package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.PublishActionOverride
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.input.T9Engine
import hrln.interactive.sidekit.design.input.T9Mode
import hrln.interactive.sidekit.design.input.T9Session
import hrln.interactive.sidekit.design.input.TextFieldT9Buffer
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * The SideKit text field. With a touch keyboard it behaves like any text field. With the T9
 * keypad module attached it never opens the virtual keyboard: keypad presses are typed with T9
 * prediction, the word being formed is highlighted inline, and the action bar turns into the
 * suggestion strip (tap a word to take it, `*` to step through them, `0` for a space).
 *
 * When not composing a word the bar shows the input mode (tap to switch: T9, multi-tap, numbers,
 * touch keyboard) and Done, which ends typing and gives the screen its own actions back.
 *
 * @param focusOnKeypad make this the screen's main field: typing on the keypad while nothing is
 *   focused starts typing here.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SidekitTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SidekitTheme.typography.body,
    placeholder: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    focusOnKeypad: Boolean = false,
    singleLine: Boolean = false,
    onDone: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val keypad = LocalKeypad.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // An auto-focused field counts as focused from its first frame, so the T9 bar is published in
    // the same frame as the screen and the action bar morphs once, not twice.
    var focused by remember { mutableStateOf(autoFocus) }
    var awaitingFocus by remember { mutableStateOf(autoFocus) }
    if (autoFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val session = remember(keypad, state) { keypad?.let { T9Session(it, TextFieldT9Buffer(state), scope) } }
    val t9 = keypad?.t9 == true

    if (focusOnKeypad && keypad != null) {
        DisposableEffect(keypad, focusRequester) {
            keypad.registerFallback(focusRequester)
            onDispose { keypad.unregisterFallback(focusRequester) }
        }
    }
    DisposableEffect(keypad, session) {
        onDispose { if (keypad?.session === session) keypad?.session = null }
    }
    // The keypad was picked up mid-edit: put the virtual keyboard away.
    LaunchedEffect(t9, focused) { if (t9 && focused) keyboard?.hide() }

    if (focused && t9 && keypad != null && session != null) {
        PublishActionOverride(
            t9Actions(
                engine = keypad.engine,
                session = session,
                onDone = {
                    session.finish()
                    focusManager.clearFocus()
                    onDone?.invoke()
                },
                onTouchKeyboard = {
                    session.finish()
                    keypad.enableT9(false)
                    keyboard?.show()
                },
            ),
            // The Options key while typing picks the input mode directly.
            options = T9Mode.entries.map { mode ->
                val label = when (mode) {
                    T9Mode.Predictive -> "T9"
                    T9Mode.MultiTap -> "abc"
                    T9Mode.Numeric -> "123"
                }
                SideAction(
                    "mode.${mode.name}",
                    label,
                    tone = if (keypad.engine.mode == mode) ActionTone.Primary else ActionTone.Muted,
                    contentDescription = "Input mode $label",
                ) { session.setMode(mode) }
            },
        )
    }

    val colors = SidekitTheme.colors
    val style = remember(textStyle, colors.light) { textStyle.copy(color = colors.light) }
    val scroll = rememberScrollState()
    val layout = remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val highlightColor = colors.primaryA1.copy(alpha = 0.55f)
    // Read in the draw phase only: composing a word redraws the field, it doesn't recompose it.
    val highlight: () -> TextRange? = { if (focused && keypad?.session === session) keypad?.engine?.highlight else null }
    BasicTextField(
        state = state,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { f ->
                if (awaitingFocus && !f.isFocused) return@onFocusChanged
                awaitingFocus = false
                focused = f.isFocused
                if (keypad == null || session == null) return@onFocusChanged
                if (f.isFocused) {
                    keypad.session = session
                    keypad.prepare()
                } else if (keypad.session === session) {
                    session.finish()
                    keypad.session = null
                }
            }
            // Before the IME: on the Sidephone the virtual keyboard stays connected even when hidden,
            // and must not see keypad presses while T9 is typing.
            .onInterceptKeyBeforeSoftKeyboard { event ->
                if (keypad == null || session == null) return@onInterceptKeyBeforeSoftKeyboard false
                val native = event.nativeKeyEvent
                keypad.observe(native)
                if (!keypad.t9) return@onInterceptKeyBeforeSoftKeyboard false
                val enter = native.keyCode == android.view.KeyEvent.KEYCODE_ENTER || native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                if (enter && singleLine) {
                    // A one-line field has no newline: Enter / OK submits it.
                    if (native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        session.finish()
                        onDone?.invoke()
                    }
                    true
                } else {
                    session.onKeyEvent(native)
                }
            },
        textStyle = style,
        cursorBrush = SolidColor(colors.light),
        keyboardOptions = KeyboardOptions(
            showKeyboardOnFocus = !t9,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
        ),
        onKeyboardAction = if (onDone != null) KeyboardActionHandler { onDone() } else null,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
        scrollState = scroll,
        onTextLayout = { getResult -> layout.value = getResult },
        decorator = { field ->
            Box(
                Modifier.drawBehind {
                    // The T9 word being formed: a soft highlight behind the text, drawn from the text
                    // layout, so the cursor stays a plain caret (no selection handles).
                    val range = highlight() ?: return@drawBehind
                    val result = layout.value?.invoke() ?: return@drawBehind
                    val len = result.layoutInput.text.length
                    if (range.collapsed || range.max > len) return@drawBehind
                    val path = result.getPathForRange(range.min, range.max)
                    val dx = if (singleLine) -scroll.value.toFloat() else 0f
                    val dy = if (singleLine) 0f else -scroll.value.toFloat()
                    translate(dx, dy) { drawPath(path, highlightColor) }
                },
            ) {
                if (placeholder != null && state.text.isEmpty()) SidekitText(placeholder, style = textStyle, color = colors.surfaceA6)
                field()
            }
        },
    )
}

/**
 * [SidekitTextField] for a plain `String` value. The field keeps its own [TextFieldState] (T9
 * composes inside it) and reports text changes through [onValueChange].
 */
@Composable
fun SidekitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SidekitTheme.typography.body,
    placeholder: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    focusOnKeypad: Boolean = false,
    singleLine: Boolean = false,
    onDone: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val state = rememberTextFieldState(value)
    val latest by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { latest(it) }
    }
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    SidekitTextField(state, modifier, textStyle, placeholder, focusRequester, focusOnKeypad, singleLine, onDone, autoFocus)
}

/**
 * The T9 action bar. Keys are positional (`t9.0`, `t9.1`, `t9.2`) so the bar's buttons are reused
 * as the suggestions change: each key press swaps labels in place and the strip grows or shrinks
 * between one and three buttons, instead of rebuilding.
 */
private fun t9Actions(engine: T9Engine, session: T9Session, onDone: () -> Unit, onTouchKeyboard: () -> Unit): List<SideAction> {
    if (engine.composing) {
        val candidates = engine.candidates
        if (engine.unknown) {
            return listOf(
                SideAction("t9.0", candidates.firstOrNull() ?: "?", tone = ActionTone.Muted, dense = true, weight = 1.4f, contentDescription = "No match") {},
                SideAction("t9.1", "Spell", tone = ActionTone.Primary, dense = true, motion = ActionMotion.Up, contentDescription = "Spell with multi-tap") {
                    session.setMode(T9Mode.MultiTap)
                },
            )
        }
        return candidates.take(MAX_SHOWN).mapIndexed { i, word ->
            val chosen = i == engine.selected
            SideAction(
                key = "t9.$i",
                label = word,
                tone = if (chosen) ActionTone.Primary else ActionTone.Muted,
                // Longer words get more room so suggestions rarely truncate.
                weight = 1f + 0.12f * word.length + if (chosen) 0.4f else 0f,
                dense = true,
                contentDescription = "Suggestion $word",
            ) { session.pick(i) }
        }
    }
    return listOf(
        SideAction("t9.0", engine.modeLabel, tone = ActionTone.Muted, weight = 0.8f, dense = true, motion = ActionMotion.Up, contentDescription = "Input mode ${engine.modeLabel}") {
            when (engine.mode) {
                T9Mode.Predictive -> session.setMode(T9Mode.MultiTap)
                T9Mode.MultiTap -> session.setMode(T9Mode.Numeric)
                T9Mode.Numeric -> {
                    session.setMode(T9Mode.Predictive)
                    onTouchKeyboard()
                }
            }
        },
        SideAction("t9.1", "Done", SidekitIcon.Check, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up, contentDescription = "Done typing", onClick = onDone),
    )
}

private const val MAX_SHOWN = 3

/** Whether T9 typing is active anywhere (for screens that adapt, e.g. to keep their title visible). */
@Composable
fun rememberT9Active(): Boolean = LocalKeypad.current?.t9 == true
