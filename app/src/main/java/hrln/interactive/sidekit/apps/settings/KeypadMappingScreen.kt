package hrln.interactive.sidekit.apps.settings

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.KeyMapper
import hrln.interactive.sidekit.design.input.KeypadControl
import hrln.interactive.sidekit.design.input.KeypadControls
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.input.PhysicalKey
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.delay

private val MAPPING = "${SubApp.Settings.route}/keypad-mapping"
private val TESTER = "$MAPPING/tester"
private val LEARN = "$MAPPING/key/{module}/{code}"

internal fun keypadMappingRoute() = MAPPING

/** Settings > Keypad mapping, its key capture and its key tester. */
internal fun NavGraphBuilder.keypadMappingGraph(nav: NavHostController) {
    composable(MAPPING) {
        KeypadMappingScreen(
            back = { nav.popBackStack() },
            learn = { module, control -> nav.navigate("$MAPPING/key/${module.name}/${control.keyCode}") },
            tester = { nav.navigate(TESTER) },
        )
    }
    composable(LEARN) { entry ->
        val module = KeypadModule.entries.firstOrNull { it.name == entry.arguments?.getString("module") }
        val control = module?.let { m -> entry.arguments?.getString("code")?.toIntOrNull()?.let { KeypadControls.find(m, it) } }
        if (module == null || control == null) {
            LaunchedEffect(Unit) { nav.popBackStack() }
            return@composable
        }
        KeyCaptureScreen(module, control, done = { nav.popBackStack() })
    }
    composable(TESTER) { KeyTesterScreen { nav.popBackStack() } }
}

/**
 * The mapping editor. Automatic (the default) leaves keys alone and detects the module; Custom
 * picks a module, pauses detection, and lets every control of that module be moved to another key.
 */
@Composable
internal fun KeypadMappingScreen(back: () -> Unit, learn: (KeypadModule, KeypadControl) -> Unit, tester: () -> Unit) {
    val keypad = LocalKeypad.current
    val mapper = keypad?.mapper
    val custom = mapper?.customModule
    val bindings = custom?.let { mapper.bindings(it) }.orEmpty()
    val list = rememberLazyListState()
    fun toggle() { keypad?.useCustomMapping(if (custom == null) keypad.module else null) }
    fun nextLayout() { custom?.let { keypad.useCustomMapping(KeypadModule.entries[(it.ordinal + 1) % KeypadModule.entries.size]) } }
    val options = buildList {
        if (custom != null && bindings.isNotEmpty()) add(SideAction("reset", "Reset keys", SidekitIcon.Undo, tone = ActionTone.Destructive) { mapper.resetAll(custom) })
        if (custom != null) add(SideAction("automatic", "Use automatic", tone = ActionTone.Muted) { keypad.useCustomMapping(null) })
    }
    SidekitScaffold("Keypad mapping", actions = listOf(SideAction.back("back", "Settings", back)), options = options) { padding ->
        SidekitList(padding, state = list) {
            item {
                SidekitItem("Mapping", Modifier.height(CompactRowHeight).listFade(list, 0), secondary = if (custom == null) "Automatic" else "Custom", onClick = ::toggle)
            }
            if (custom != null) item { SidekitItem("Layout", Modifier.height(CompactRowHeight).listFade(list, 1), secondary = custom.label, onClick = ::nextLayout) }
            item { SidekitItem("Key tester", Modifier.height(CompactRowHeight).listFade(list, 2), showLinkArrow = true, onClick = tester) }
            item {
                SidekitText(
                    if (custom == null) "Sidekit recognises Sidephone keypad modules from their keys. Turn on Custom for a keypad it doesn’t know, or to move keys around."
                    else "Detection is paused. Choose a key to teach Sidekit which button to use for it. Keys you don’t change keep working as before.",
                    style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE,
                )
            }
            if (custom != null) {
                itemsIndexed(KeypadControls.of(custom), key = { _, c -> c.keyCode }) { index, control ->
                    val bound = bindings[control.keyCode]
                    SidekitItem(
                        control.label, Modifier.height(CompactRowHeight).listFade(list, index + 4),
                        secondary = bound?.label ?: "Default",
                        // Moved keys stand out; the rest read as defaults.
                        icon = if (bound != null) SidekitIcon.Edit else null,
                        onClick = { learn(custom, control) },
                    )
                }
                if (bindings.isNotEmpty()) item {
                    SidekitLineButton("Reset ${custom.label} keys", { mapper.resetAll(custom) }, icon = SidekitIcon.Undo, variant = LineButtonVariant.Destructive)
                }
            }
        }
    }
}

/** Where a key capture is. */
internal sealed interface CaptureStep {
    /** Listening for a key; gives up after [secondsLeft]. */
    data class Waiting(val secondsLeft: Int) : CaptureStep
    /** Heard [key]. [movesFrom] is the control it is bound to now, if any; [guarded] means it is the Back or Options key. */
    data class Heard(val key: PhysicalKey, val movesFrom: KeypadControl?, val guarded: Boolean) : CaptureStep
}

/**
 * Learns the key for one control. Waiting, every press is captured (none of them act, so a key
 * can be learned whatever it does now); the wait gives up on its own, and touch Cancel or the
 * system Back always leave. Once a key is heard, pressing it again saves it, and every other key
 * works as usual again: Back cancels, the D-pad reaches Save / Try again. Back and Options can
 * only be moved with Save, so pressing them to escape never maps them by accident.
 */
@Composable
internal fun KeyCaptureScreen(module: KeypadModule, control: KeypadControl, done: () -> Unit) {
    val mapper = LocalKeypad.current?.mapper
    var attempt by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf<CaptureStep>(CaptureStep.Waiting(CAPTURE_SECONDS)) }
    val latestDone by rememberUpdatedState(done)
    fun save(key: PhysicalKey) {
        mapper?.bind(module, control.keyCode, key)
        latestDone()
    }
    if (mapper != null) {
        DisposableEffect(mapper, module, control.keyCode) {
            val stop = mapper.capture { key, event ->
                when (val now = step) {
                    is CaptureStep.Waiting -> {
                        // The keypad service turns the Sidephone's top-left key into the system's Back:
                        // that's the way out, not a key to learn.
                        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD && event.keyCode == KeyEvent.KEYCODE_BACK) latestDone()
                        else step = heard(mapper, module, control, key)
                        true
                    }
                    is CaptureStep.Heard -> if (key == now.key && !now.guarded) { save(key); true } else false
                }
            }
            onDispose { stop() }
        }
    }
    val waiting = step as? CaptureStep.Waiting
    LaunchedEffect(attempt, waiting != null) {
        if (waiting == null) return@LaunchedEffect
        for (left in CAPTURE_SECONDS - 1 downTo 0) {
            delay(1_000)
            step = CaptureStep.Waiting(left)
        }
        latestDone()
    }
    KeyCaptureContent(module, control, step, cancel = done, retry = { attempt++; step = CaptureStep.Waiting(CAPTURE_SECONDS) },
        save = { (step as? CaptureStep.Heard)?.let { save(it.key) } })
}

private fun heard(mapper: KeyMapper, module: KeypadModule, control: KeypadControl, key: PhysicalKey): CaptureStep.Heard {
    val now = mapper.controlFor(module, key) ?: key.keyCode
    val movesFrom = KeypadControls.find(module, now)?.takeIf { it.keyCode != control.keyCode }
    val guarded = now == KeyEvent.KEYCODE_BACK || now == KeyEvent.KEYCODE_ENDCALL || now == KeyEvent.KEYCODE_MENU
    return CaptureStep.Heard(key, movesFrom, guarded && control.keyCode != now)
}

@Composable
internal fun KeyCaptureContent(module: KeypadModule, control: KeypadControl, step: CaptureStep, cancel: () -> Unit, retry: () -> Unit, save: () -> Unit) {
    val heard = step as? CaptureStep.Heard
    val actions = if (heard == null) listOf(SideAction.back("cancel", "Cancel", cancel))
    else listOf(
        SideAction("retry", "Try again", SidekitIcon.Undo, tone = ActionTone.Muted, onClick = retry),
        SideAction("save", "Save", SidekitIcon.Check, tone = ActionTone.Secondary, onClick = save),
    )
    if (heard != null) BackHandler(onBack = cancel)
    SidekitScaffold(if (heard == null) "Press a key" else "Press it again", actions = actions) { padding ->
        SidekitList(padding) {
            item {
                SidekitCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)) {
                        SidekitText("${module.label} · ${control.label}", style = SidekitTheme.typography.headline)
                        SidekitText(control.hint, style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            when (step) {
                is CaptureStep.Waiting -> {
                    item {
                        SidekitItem("Listening…", Modifier.height(CompactRowHeight), secondary = "${step.secondsLeft} s", colour = ItemColour.Muted)
                    }
                    item {
                        SidekitText("Press the button on your keypad you want to use for ${control.label}. Tap Cancel to stop.",
                            style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
                is CaptureStep.Heard -> {
                    item {
                        SidekitItem(step.key.label, Modifier.height(CompactRowHeight), secondary = step.key.device.ifEmpty { "Keypad" }, icon = SidekitIcon.Check)
                    }
                    item {
                        val text = when {
                            step.guarded -> "This is your ${step.movesFrom?.label ?: "Options"} key. Tap Save to use it for ${control.label} instead."
                            step.movesFrom != null -> "Press it again to save. It moves from ${step.movesFrom.label}, which goes back to its usual key."
                            else -> "Press it again to save, or tap Try again."
                        }
                        SidekitText(text, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            }
        }
    }
}

/** One press seen by the key tester; [mappedTo] is the control it became, if the mapping changed it. */
internal data class TestedKey(val key: PhysicalKey, val scanCode: Int, val mappedTo: String?)

/** Shows what each key press sends and what SideKit makes of it: for setting up a new keypad. */
@Composable
internal fun KeyTesterScreen(back: () -> Unit) {
    val keypad = LocalKeypad.current
    val presses = remember { mutableStateListOf<TestedKey>() }
    if (keypad != null) {
        DisposableEffect(keypad) {
            val stop = keypad.mapper.watch { key, out ->
                val mappedTo = if (out.keyCode == key.keyCode) null
                else KeypadControls.find(keypad.module, out.keyCode)?.label ?: PhysicalKey.keyName(out.keyCode)
                presses.add(0, TestedKey(key, out.scanCode, mappedTo))
                if (presses.size > MAX_TESTED) presses.removeAt(presses.lastIndex)
            }
            onDispose { stop() }
        }
    }
    KeyTesterContent(keypad?.module, presses, back)
}

@Composable
internal fun KeyTesterContent(module: KeypadModule?, presses: List<TestedKey>, back: () -> Unit) {
    val list = rememberLazyListState()
    SidekitScaffold("Key tester", actions = listOf(SideAction.back("back", "Mapping", back))) { padding ->
        SidekitList(padding, state = list) {
            item {
                SidekitText(
                    "Press keys to see what they send and what they do in ${module?.label ?: "Sidekit"}. Back still goes back.",
                    style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE,
                )
            }
            items(presses.size) { index ->
                val press = presses[index]
                SidekitCard(Modifier.fillMaxWidth().listFade(list, index + 1)) {
                    Column {
                        SidekitText(press.mappedTo?.let { "${press.key.label} → $it" } ?: press.key.label, style = SidekitTheme.typography.body)
                        val scan = if (press.scanCode != 0) " · scan ${press.scanCode}" else ""
                        SidekitText("${press.key.device.ifEmpty { "Keypad" }} · code ${press.key.keyCode}$scan", style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6)
                    }
                }
            }
        }
    }
}

private const val CAPTURE_SECONDS = 10
private const val MAX_TESTED = 8
