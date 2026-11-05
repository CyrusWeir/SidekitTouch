package hrln.interactive.sidekit.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.apps.settings.*
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.input.*
import hrln.interactive.sidekit.design.theme.SidekitTheme

@Composable
internal fun OnboardingScreen(onFinish: () -> Unit, replay: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { SidekitPreferences(context) }
    var step by rememberSaveable { mutableIntStateOf(if (replay) 0 else prefs.setupStep) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val keypad = LocalKeypad.current
    val hidden = rememberHiddenApps(prefs)
    val ported by remember { PortedApps.get(context).apps }.collectAsStateWithLifecycle()
    val selectedColour = rememberCaseColour(prefs)
    var listLayout by remember { mutableStateOf(prefs.listLayout) }
    var inputMode by remember { mutableStateOf(prefs.inputMode) }
    var tourIndex by rememberSaveable { mutableIntStateOf(0) }
    val modules = remember(keypad?.module) { orderedModules(keypad?.module ?: KeypadModule.T9) }
    LaunchedEffect(keypad?.module) { tourIndex = 0 }
    val access = remember { setupAccess() }
    val requested = remember { mutableStateListOf<String>() }
    val result = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val permissionResult = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        if (it.values.any { granted -> !granted }) message = "Some access was not granted. You can continue, or manage it in App settings."
    }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    fun move(next: Int) { step = next; message = null; if (!replay) prefs.setupStep = next }
    fun finish() { prefs.setupComplete = true; prefs.setupStep = 0; onFinish() }
    fun open(intent: Intent) {
        try { result.launch(intent) } catch (_: android.content.ActivityNotFoundException) { message = "This settings screen is unavailable on this device." }
        catch (_: SecurityException) { message = "Android could not open this setting. You can continue and change it in Settings." }
    }
    fun request(entry: SetupAccess) {
        message = null
        if (entry.intent != null) open(entry.intent.invoke(context))
        else if (entry.title in requested) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        else { requested.add(entry.title); permissionResult.launch(entry.permissions.toTypedArray()) }
    }
    val titles = listOf("Hello, Sidekit", "Make it yours", "Your home screen", "Choose your apps", "Make it work", "Your typing style", "Meet your keypad")
    BackHandler { if (step > 0) move(step - 1) else if (replay) onFinish() }
    SidekitScaffold(titles[step], actions = buildList {
        if (step > 0 || replay) add(SideAction.back("back", "Back") { if (step > 0) move(step - 1) else onFinish() })
        add(SideAction.forward("next", if (step == 6) "Finish" else "Next", { if (step == 6) finish() else move(step + 1) }))
    }) { padding ->
        key(step) {
            val listState = rememberLazyListState()
            LaunchedEffect(tourIndex) { if (step == 6) listState.scrollToItem(0) }
            SidekitList(padding, state = listState) {
                if (step != 6) item { SidekitText("${step + 1} / 7 · SIDEPHONE SETUP", style = SidekitTheme.typography.label) }
                when (step) {
                    0 -> {
                        item { Copy("A home that feels like you", "Choose your colours, layout and controls. Your choices save as you go.") }
                        item {
                            val enabled = remember(revision) { homeAccess.enabled(context) }
                            SidekitLineButton(if (enabled) "Sidekit is your home app ✓" else "Set as home app", { request(homeAccess) })
                        }
                        item { Copy("Always one press away", "Choose Sidekit in Android’s home app prompt. You can also tap Next and do this later in Settings.") }
                    }
                    1 -> caseColourChoices(selectedColour) { prefs.caseColour = it }
                    2 -> {
                        item { Copy("Grid or list?", "Change this any time in Settings.") }
                        item { LayoutChoice(false, listLayout) { listLayout = false; prefs.listLayout = false } }
                        item { LayoutChoice(true, listLayout) { listLayout = true; prefs.listLayout = true } }
                    }
                    3 -> hiddenAppChoices(hidden, ported) { prefs.hiddenApps = it }
                    4 -> {
                        item { Copy("You’re in control", "Enable access for the features you want, or continue without it. Status updates when you return.") }
                        access.forEach { entry ->
                            item {
                                val enabled = remember(revision) { entry.enabled(context) }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SidekitLineButton("${if (enabled) "✓ " else ""}${entry.title}", { request(entry) }, variant = if (enabled) LineButtonVariant.Secondary else LineButtonVariant.Primary)
                                    SidekitText(if (enabled) "Enabled · ${entry.reason}" else entry.reason, style = SidekitTheme.typography.label.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = Int.MAX_VALUE)
                                }
                            }
                        }
                        item { Copy("Accounts come later", "Connect mail and other accounts inside their apps. Screen lock and network access use Android’s existing settings; there is no extra permission prompt here.") }
                    }
                    5 -> {
                        item { Copy("Letters or numbers?", "Choose how text fields start. Hold # (T9) or aA (Mini QWERTY) to switch while typing.") }
                        T9Mode.entries.forEach { mode -> item {
                            val label = if (mode == T9Mode.Numeric) "Numbers" else "Letters · multi-tap"
                            SidekitLineButton("${if (inputMode == mode) "✓ " else ""}$label", {
                                inputMode = mode; prefs.inputMode = mode; keypad?.let(prefs::applyInputDefault)
                            }, variant = if (inputMode == mode) LineButtonVariant.Primary else LineButtonVariant.Secondary)
                        } }
                        item { Copy("Words as you type", "Suggestions appear in the action bar. Select a suggestion to complete a word. Your typing preference can be changed in Settings.") }
                    }
                    6 -> {
                        item { SidekitText("${tourIndex + 1} / ${modules.size} · ${modules[tourIndex].label}", style = SidekitTheme.typography.headline) }
                        item { KeypadDemo(modules[tourIndex]) }
                        item { SidekitText("${keypad?.module?.label ?: "T9"} is your current selection. Press a letter, digit or media key to detect yours.", style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) }

                        item { SidekitLineButton("Skip / next keypad", { tourIndex = (tourIndex + 1) % modules.size }, variant = LineButtonVariant.Secondary) }
                        item { SidekitLineButton("Use ${modules[tourIndex].label}", { keypad?.useModule(modules[tourIndex]) }) }
                        item { Copy("Ready when you are", "Tap Finish to open your home screen. Revisit this tour from Settings → Setup & keypad tour.") }
                    }
                }
                message?.let { item { Copy("Access update", it) } }
            }
        }
    }
}

@Composable
private fun Copy(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SidekitText(title, style = SidekitTheme.typography.headline.copy(fontSize = 30.sp, lineHeight = 36.sp), maxLines = Int.MAX_VALUE)
        SidekitText(body, style = SidekitTheme.typography.label.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = Int.MAX_VALUE)
    }
}

@Composable
private fun LayoutChoice(list: Boolean, selected: Boolean, choose: () -> Unit) {
    SidekitTile(Modifier.fillMaxWidth().height(112.dp), color = if (list == selected) SidekitTheme.colors.primaryA1 else SidekitTheme.colors.surfaceA3, onClick = choose) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SidekitText("${if (list == selected) "✓ " else ""}${if (list) "List" else "Grid"}", style = SidekitTheme.typography.headline.copy(fontSize = 30.sp, lineHeight = 36.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(90.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(if (list) 3 else 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(if (list) 1 else 3) {
                                Box(Modifier.weight(1f).height(if (list) 10.dp else 18.dp).clip(SidekitTheme.shapes.item).background(SidekitTheme.colors.surfaceA6))
                            }
                        }
                    }
                }
                SidekitText(if (list) "App names in a simple list" else "Large tiles, easy to spot", style = SidekitTheme.typography.label.copy(fontSize = 22.sp, lineHeight = 26.sp), maxLines = 2)
            }
        }
    }
}

internal fun orderedModules(current: KeypadModule) = listOf(current) + KeypadModule.entries.filter { it != current }
