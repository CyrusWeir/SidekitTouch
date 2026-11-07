package hrln.interactive.sidekit.apps.settings

import hrln.interactive.sidekit.onboarding.OnboardingScreen
import android.app.ActivityManager
import android.app.role.RoleManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.design.theme.SidekitTheme
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.T9Mode
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    composable(SubApp.Settings.route) {
        SettingsHome(
            onHome = { nav.popBackStack() },
            onAbout = { nav.navigate("${SubApp.Settings.route}/about") },
            onCaseColour = { nav.navigate("${SubApp.Settings.route}/case-colour") },
            onSetup = { nav.navigate("${SubApp.Settings.route}/setup") },
            onHiddenApps = { nav.navigate("${SubApp.Settings.route}/hidden-apps") },
            onSystem = { nav.navigate("${SubApp.Settings.route}/system") },
            onKeypadMapping = { nav.navigate(keypadMappingRoute()) },
            onArrange = { nav.navigate("${SubApp.Settings.route}/arrange") },
        )
    }
    keypadMappingGraph(nav)
    composable("${SubApp.Settings.route}/hidden-apps") { HiddenAppsScreen { nav.popBackStack() } }
    composable("${SubApp.Settings.route}/system") { SystemSettingsScreen { nav.popBackStack() } }
    composable("${SubApp.Settings.route}/setup") { OnboardingScreen(onFinish = { nav.popBackStack() }, replay = true) }
    composable("${SubApp.Settings.route}/case-colour") { CaseColourScreen { nav.popBackStack() } }
    composable("${SubApp.Settings.route}/arrange") { ArrangeHomeScreen { nav.popBackStack() } }
    composable("${SubApp.Settings.route}/about") { AboutScreen { nav.popBackStack() } }
}

@Composable
internal fun SettingsHome(onHome: () -> Unit, onAbout: () -> Unit, onCaseColour: () -> Unit = {}, onSetup: () -> Unit = {}, onHiddenApps: () -> Unit = {}, onSystem: () -> Unit = {}, onKeypadMapping: () -> Unit = {}, onArrange: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { SidekitPreferences(context) }
    val caseColour = rememberCaseColour(prefs)
    val keypad = LocalKeypad.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    val accessibility = remember(revision) {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it.packageName == context.packageName && it.className.endsWith("KeypadAccessibilityService") }
    }
    val home = remember(revision) { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }
    fun open(action: String) { try { context.startActivity(Intent(action)) } catch (_: Exception) { message = "This settings screen is unavailable on this device." } }
    val listLayout = remember(revision) { prefs.listLayout }
    val compactLayout = remember(revision) { prefs.compactLayout }
    val inputMode = remember(revision) { prefs.inputMode }
    val list = rememberLazyListState()
    SidekitScaffold("Settings", actions = listOf(SideAction.back("home", "Home", onHome), SideAction.forward("about", "About", onAbout))) { padding ->
        SidekitList(padding, state = list) {
            item { SidekitItem("Launcher default", Modifier.height(CompactRowHeight).listFade(list, 0), secondary = if (listLayout) "List" else "Grid", onClick = { prefs.listLayout = !prefs.listLayout; revision++ }) }
            item { SidekitItem("Arrange apps", Modifier.height(CompactRowHeight).listFade(list, 1), secondary = "Home order & dock", onClick = onArrange) }
            item { SidekitItem("Case colour", Modifier.height(CompactRowHeight).listFade(list, 2), secondary = caseColour?.label ?: "Original blue", onClick = onCaseColour) }
            // Shorter header and action bar on every screen: more room for the body.
            item { SidekitItem("Compact layout", Modifier.height(CompactRowHeight).listFade(list, 3), secondary = if (compactLayout) "On" else "Off", onClick = { prefs.compactLayout = !prefs.compactLayout; revision++ }) }
            // Detected from the keys pressed; tapping overrides it until another module's key is seen.
            // A custom mapping fixes the module, so then the row opens the mapping instead.
            val custom = keypad?.mapper?.customModule
            item { SidekitItem("Keypad", Modifier.height(CompactRowHeight).listFade(list, 4), secondary = custom?.let { "${it.label} · custom" } ?: keypad?.module?.label ?: "None", onClick = { if (custom != null) onKeypadMapping() else keypad?.let { it.useModule(KeypadModule.entries[(it.module.ordinal + 1) % KeypadModule.entries.size]) }; revision++ }) }
            item { SidekitItem("Input mode", Modifier.height(CompactRowHeight).listFade(list, 5), secondary = if (inputMode == T9Mode.Numeric) "Numbers" else "Letters", onClick = { prefs.inputMode = T9Mode.entries[(prefs.inputMode.ordinal + 1) % T9Mode.entries.size]; keypad?.let { prefs.applyInputDefault(it) }; revision++ }) }
            item { SidekitItem("Keypad Back key", Modifier.height(CompactRowHeight).listFade(list, 6), secondary = if (accessibility) "Enabled" else "Enable", onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) }
            item { SidekitItem("Home app", Modifier.height(CompactRowHeight).listFade(list, 7), secondary = if (home) "Sidekit" else "Choose", onClick = { open(Settings.ACTION_HOME_SETTINGS) }) }
            item { SidekitItem("Hide apps", showLinkArrow = true, onClick = onHiddenApps) }
            // Device settings live on their own page, so this screen stays Sidekit's own settings.
            item { SidekitItem("System settings", showLinkArrow = true, onClick = onSystem) }
            // Advanced: custom mapping for keypads Sidekit doesn't recognise, or keys moved around.
            item { SidekitItem("Keypad mapping", secondary = if (custom != null) "Custom" else null, showLinkArrow = custom == null, onClick = onKeypadMapping) }
            item { SidekitItem("Setup & keypad tour", showLinkArrow = true, onClick = onSetup) }
            message?.let { item { SidekitCard { SidekitText(it, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) } } }
        }
    }
}

/** Device settings (Wi-Fi, display, sound, …), which open Android's own pages. */
@Composable
internal fun SystemSettingsScreen(back: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    fun open(action: String) { try { context.startActivity(Intent(action)) } catch (_: Exception) { message = "This settings screen is unavailable on this device." } }
    val list = rememberLazyListState()
    SidekitScaffold("System settings", actions = listOf(SideAction.back("back", "Settings", back))) { padding ->
        SidekitList(padding, state = list) {
            itemsIndexed(SettingsUiState().rows) { index, row -> SidekitItem(row.label, Modifier.height(CompactRowHeight).listFade(list, index), secondary = row.value, onClick = { open(row.key.settingsAction()) }) }
            message?.let { item { SidekitCard { SidekitText(it, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) } } }
        }
    }
}

/** Retained for design previews and screen-level tests. */
@Composable
fun SettingsScreen(rows: List<SettingRow>, onRowClick: (SettingKey) -> Unit, onHome: () -> Unit = {}, onAbout: () -> Unit = {}) {
    val list = rememberLazyListState()
    SidekitScaffold("Settings", actions = listOf(SideAction.back("home", "Home", onHome), SideAction.forward("about", "About", onAbout))) { padding ->
        SidekitList(padding, state = list) { itemsIndexed(rows) { index, row -> SidekitItem(row.label, Modifier.height(CompactRowHeight).listFade(list, index), secondary = row.value, onClick = { onRowClick(row.key) }) } }
    }
}

@Composable
private fun AboutScreen(back: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(listOf("Device" to "${Build.MANUFACTURER} ${Build.MODEL}", "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "Build" to Build.DISPLAY)) }
    LaunchedEffect(Unit) {
        val summaries = withContext(Dispatchers.IO) {
            val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val storage = runCatching {
                val stats = context.getSystemService(StorageStatsManager::class.java)
                "${Formatter.formatFileSize(context, stats.getFreeBytes(StorageManager.UUID_DEFAULT))} free / ${Formatter.formatFileSize(context, stats.getTotalBytes(StorageManager.UUID_DEFAULT))}"
            }.getOrDefault("Unavailable")
            listOf("Sidekit" to context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
                "Storage" to storage, "RAM" to "${Formatter.formatFileSize(context, memory.availMem)} available / ${Formatter.formatFileSize(context, memory.totalMem)}",
                "Open-source notices" to "AndroidX / Jetpack Compose — Apache License 2.0. Kotlin — Apache License 2.0.",
                "T9 word list" to context.assets.open("t9/NOTICE").bufferedReader().use { it.readText() })
        }
        rows = rows + summaries
    }
    val list = rememberLazyListState()
    SidekitScaffold("About", actions = listOf(SideAction.back("back", "Settings", back))) { padding ->
        SidekitList(padding, state = list) { itemsIndexed(rows) { index, row -> SidekitCard(Modifier.listFade(list, index)) {
                Column { SidekitText(row.first, style = SidekitTheme.typography.headline); SidekitText(row.second, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) }
            } } }
    }
}
