package hrln.interactive.sidekit.apps.sideport

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.apps.apps.AppRow
import hrln.interactive.sidekit.apps.apps.AppSearchField
import hrln.interactive.sidekit.apps.apps.AppsRepository
import hrln.interactive.sidekit.apps.apps.LaunchableApp
import hrln.interactive.sidekit.apps.apps.matchesApp
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.apps.sideport.bridge.Bridge
import hrln.interactive.sidekit.apps.sideport.bridge.Conversation
import hrln.interactive.sidekit.apps.sideport.install.ApkInstaller
import hrln.interactive.sidekit.apps.sideport.install.InstallStatus
import hrln.interactive.sidekit.apps.sideport.registry.PortMode
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.apps.sideport.service.SideportService
import hrln.interactive.sidekit.apps.sideport.ui.PortedAppIcon
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.LineButtonVariant
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitLineButton
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.system.SidekitBuild
import kotlinx.coroutines.launch

/**
 * Sideport: Android apps as Sidekit apps. Install an APK (or pick an installed app) and it opens
 * drawn in Sidekit's components by the projection service, with its chats and media also
 * available here directly through the notification and media-session bridge.
 */
fun NavGraphBuilder.sideportGraph(nav: NavHostController) {
    navigation(startDestination = SideportRoutes.HOME, route = SideportRoutes.ROOT) {
        composable(SideportRoutes.HOME) {
            SideportHomeScreen(
                onHome = { nav.popBackStack() },
                onAdd = { nav.navigate(SideportRoutes.ADD) { launchSingleTop = true } },
                onHub = { nav.navigate(SideportRoutes.hub(it)) { launchSingleTop = true } }
            )
        }
        composable(SideportRoutes.ADD) { SideportAddScreen(onDone = { nav.popBackStack() }) }
        composable(SideportRoutes.HUB) { entry ->
            val pkg = entry.arguments?.getString("pkg").orEmpty()
            SideportHubScreen(
                pkg,
                onBack = { nav.popBackStack() },
                onChat = { nav.navigate(SideportRoutes.chat(it)) },
                onPlayer = { nav.navigate(SideportRoutes.player(pkg)) }
            )
        }
        composable(SideportRoutes.CHAT) { entry ->
            SideportChatScreen(Uri.decode(entry.arguments?.getString("key").orEmpty()), onBack = { nav.popBackStack() })
        }
        composable(SideportRoutes.PLAYER) { entry ->
            SideportPlayerScreen(entry.arguments?.getString("pkg").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}

private fun Context.openSettings(action: String, data: Uri? = null): Boolean = try {
    startActivity(Intent(action).apply { if (data != null) setData(data) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) {
    false
}

/** Opens a ported app, explaining what's missing if it can't. Returns a message to show, if any. */
fun openPorted(context: Context, app: PortedApp): String? = when (Sideport.open(context, app)) {
    OpenResult.Opened -> null

    OpenResult.NeedsService -> {
        context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "Turn on \"Sideport projection\" in Accessibility, then open ${app.label} again."
    }

    OpenResult.NotInstalled -> "${app.label} isn't installed any more. Hold it in Sideport and choose Remove from Sideport."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SideportHomeScreen(onHome: () -> Unit, onAdd: () -> Unit, onHub: (String) -> Unit) {
    val context = LocalContext.current
    val apps by PortedApps.get(context).apps.collectAsStateWithLifecycle()
    val install by ApkInstaller.status.collectAsStateWithLifecycle()
    val connected by SideportService.connected.collectAsStateWithLifecycle()
    var bridge by remember { mutableStateOf(Bridge.isEnabled(context)) }
    var projection by remember { mutableStateOf(connected || SideportService.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        bridge = Bridge.isEnabled(context)
        projection = SideportService.instance != null || SideportService.isEnabled(context)
        onPauseOrDispose {}
    }
    var message by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    SidekitScaffold(
        "Sideport",
        actions = listOf(SideAction.back("home", "Home", onHome), SideAction.forward("add", "Add app", onAdd))
    ) { padding ->
        SidekitList(padding, state = list) {
            var index = 0
            (message ?: installMessage(install))?.let { text ->
                val i = index++
                item(key = "message") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, i), onClick = {
                        message = null
                        ApkInstaller.clear()
                    }) {
                        SidekitText(text, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            if (apps.isEmpty()) {
                val i = index++
                item(key = "intro") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, i)) {
                        SidekitText(
                            "Add an Android app and it opens here as a Sidekit app: its screens redrawn with Sidekit's lists and action bar, its chats and music right in Sidekit.",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
            val start = index
            itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                SidekitItem(
                    app.label,
                    Modifier.listFade(list, start + i).combinedClickable(
                        onClick = { message = openPorted(context, app) },
                        onLongClick = { onHub(app.packageName) },
                        onLongClickLabel = "${app.label} messages, media and options"
                    ),
                    secondary = if (app.mode == PortMode.Original) "Original" else null,
                    icon = SidekitIcon.DeployedCode
                )
            }
            index += apps.size
            val setup = index
            item(key = "projection") {
                SidekitItem(
                    "Projection",
                    Modifier.listFade(list, setup),
                    colour = ItemColour.Muted,
                    secondary = if (projection) "On" else "Turn on",
                    onClick = { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                )
            }
            item(key = "bridge") {
                SidekitItem(
                    "Chats & media",
                    Modifier.listFade(list, setup + 1),
                    colour = ItemColour.Muted,
                    secondary = if (bridge) "On" else "Turn on",
                    onClick = { context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                )
            }
        }
    }
}

private fun installMessage(status: InstallStatus): String? = when (status) {
    InstallStatus.Idle -> null
    is InstallStatus.Working -> status.step
    is InstallStatus.Confirming -> "Confirm installing ${status.label}."
    is InstallStatus.Installed -> "${status.label} installed and added."
    is InstallStatus.Failed -> "Install failed: ${status.message}"
}

/**
 * Installed apps that can become Sideport apps: no Sidekit build (Sidekit never ports itself or a
 * sibling), nothing already ported, one row per package, matching [query].
 */
internal fun sideportCandidates(
    installed: List<LaunchableApp>,
    ported: List<PortedApp>,
    query: String
): List<LaunchableApp> {
    val have = ported.map { it.packageName }.toSet()
    return installed
        .filter { !SidekitBuild.isSidekit(it.component.packageName) && it.component.packageName !in have && matchesApp(it.label, query) }
        .distinctBy { it.component.packageName }
}

/** Install an APK, or turn an app that's already installed into a Sideport app. */
@Composable
internal fun SideportAddScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ported = PortedApps.get(context)
    val repository = remember { AppsRepository(context.applicationContext) }
    val installed by remember { repository.apps }.collectAsStateWithLifecycle(emptyList())
    val portedApps by ported.apps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { ApkInstaller.install(context, uri) }
            onDone()
        }
    }
    val candidates = remember(installed, portedApps, query) { sideportCandidates(installed, portedApps, query) }
    val list = rememberLazyListState()
    SidekitScaffold("Add app", actions = listOf(SideAction.back("back", "Sideport", onDone))) { padding ->
        SidekitList(padding, state = list) {
            item(key = "apk") {
                SidekitItem("Install APK file", Modifier.listFade(list, 0), icon = SidekitIcon.Add, colour = ItemColour.Secondary, onClick = {
                    if (!ApkInstaller.canInstall(context)) {
                        context.openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        message = "Allow Sidekit to install apps, then choose Install APK file again."
                    } else {
                        picker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"))
                    }
                })
            }
            message?.let { text ->
                item(key = "message") {
                    SidekitCard(Modifier.fillMaxWidth(), onClick = { message = null }) {
                        SidekitText(text, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            item(key = "search") { AppSearchField(query) { query = it } }
            itemsIndexed(candidates, key = { _, a -> a.key }) { i, app ->
                AppRow(app, repository, Modifier.listFade(list, i + 2), onClick = {
                    ported.add(app.component.packageName, app.label)
                    onDone()
                })
            }
        }
    }
}

/** A removal offered on an app's hub, confirmed in the action bar before it happens. */
internal enum class HubRemoval(val verb: String) {
    /** Take the app out of Sideport; it stays installed. */
    Remove("Remove"),

    /** Hand the app to Android's uninstaller. */
    Uninstall("Uninstall")
}

/** What a ported app's hub shows. [version] is null while the app isn't installed. */
internal data class SideportHubUiState(
    val app: PortedApp,
    val version: String? = null,
    val chats: List<Conversation> = emptyList(),
    val mediaTitle: String? = null,
    val bridgeOn: Boolean = false,
    val message: String? = null,
    val confirming: HubRemoval? = null
)

/**
 * A ported app's home in Sidekit: what the app is, how it opens, its chats and media, and what can
 * be done with it. Remove and Uninstall live in the body rather than in Options, so they are
 * findable without the keypad; Options keeps only the system app page.
 */
@Composable
internal fun SideportHubScreen(pkg: String, onBack: () -> Unit, onChat: (String) -> Unit, onPlayer: () -> Unit) {
    val context = LocalContext.current
    val ported = PortedApps.get(context)
    val apps by ported.apps.collectAsStateWithLifecycle()
    val app = apps.firstOrNull { it.packageName == pkg }
    if (app == null) {
        SidekitScaffold("Sideport", actions = listOf(SideAction.back("back", "Back", onBack))) { padding ->
            SidekitList(padding) { item { SidekitItem("This app was removed") } }
        }
        return
    }
    val conversations by Bridge.conversations.collectAsStateWithLifecycle()
    val media by Bridge.media.collectAsStateWithLifecycle()
    val bridgeOn by Bridge.connected.collectAsStateWithLifecycle()
    val chats = remember(conversations, pkg) { Bridge.conversationsFor(pkg) }
    var version by remember(pkg) { mutableStateOf(appVersion(context, pkg)) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<HubRemoval?>(null) }
    // Coming back from the system uninstaller, the header says whether the app is still there.
    LifecycleResumeEffect(pkg) {
        version = appVersion(context, pkg)
        onPauseOrDispose {}
    }
    val mediaTitle = media[pkg]?.let { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing" }
    SideportHubContent(
        SideportHubUiState(app, version, chats, mediaTitle, bridgeOn, message, confirming),
        onBack = onBack,
        onOpen = { message = openPorted(context, app) },
        onChat = onChat,
        onPlayer = onPlayer,
        onBridge = { context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        onInfo = { context.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")) },
        onMode = { ported.setMode(pkg, if (app.mode == PortMode.Projected) PortMode.Original else PortMode.Projected) },
        onDismissMessage = { message = null },
        onAsk = { confirming = it },
        onCancel = { confirming = null },
        onRemove = {
            ported.remove(pkg)
            onBack()
        },
        onUninstall = { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    )
}

/** The app's installed version, or null when it isn't installed (any more). */
private fun appVersion(context: Context, pkg: String): String? = runCatching {
    context.packageManager.getPackageInfo(pkg, 0).versionName?.takeIf { it.isNotBlank() }
}.getOrNull()

/** The hub body, given its state: a header, how the app opens, what it holds, then Remove/Uninstall. */
@Composable
internal fun SideportHubContent(
    state: SideportHubUiState,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onChat: (String) -> Unit,
    onPlayer: () -> Unit,
    onBridge: () -> Unit,
    onInfo: () -> Unit,
    onMode: () -> Unit,
    onDismissMessage: () -> Unit,
    onAsk: (HubRemoval) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onUninstall: () -> Unit
) {
    val app = state.app
    val list = rememberLazyListState()
    // Back answers a confirmation before it leaves the hub.
    BackHandler(enabled = state.confirming != null, onBack = onCancel)
    val actions = state.confirming?.let { removal ->
        listOf(
            SideAction("back", "Cancel", tone = ActionTone.Muted, motion = ActionMotion.Up, contentDescription = "Keep ${app.label}", onClick = onCancel),
            SideAction("forward", removal.verb, SidekitIcon.DeleteForever, tone = ActionTone.Destructive, weight = 1.2f, motion = ActionMotion.Up, contentDescription = "Confirm ${removal.verb.lowercase()} ${app.label}") {
                when (removal) {
                    HubRemoval.Remove -> onRemove()

                    // The uninstaller is another activity: settle the bar first, so returning from it
                    // (uninstalled or cancelled) shows the hub again, not a stale confirmation.
                    HubRemoval.Uninstall -> {
                        onCancel()
                        onUninstall()
                    }
                }
            }
        )
    } ?: listOf(
        SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, tone = ActionTone.Muted, motion = ActionMotion.Back),
        SideAction.forward("open", "Open", onOpen)
    )
    // Everything frequent is in the body; Options keeps the one rare action, the system app page.
    val options = if (state.version != null) listOf(SideAction("info", "App info", SidekitIcon.Settings) { onInfo() }) else emptyList()
    SidekitScaffold(app.label, actions = actions, options = options) { padding ->
        SidekitList(padding, state = list) {
            var index = 0
            state.message?.let { text ->
                val i = index++
                item(key = "message") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, i), onClick = onDismissMessage) {
                        SidekitText(text, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            val header = index++
            item(key = "header") { SideportHeader(app, state.version, Modifier.listFade(list, header)) }
            if (state.version != null) {
                val mode = index++
                item(key = "mode") {
                    SidekitItem(
                        "Open as",
                        Modifier.height(CompactRowHeight).listFade(list, mode),
                        secondary = if (app.mode == PortMode.Projected) "Sideport" else "Original",
                        onClick = onMode
                    )
                }
            }
            val remove = index++
            item(key = "remove") {
                SidekitLineButton(
                    "Remove from Sideport",
                    { onAsk(HubRemoval.Remove) },
                    Modifier.height(CompactRowHeight).listFade(list, remove),
                    variant = LineButtonVariant.Secondary
                )
            }
            if (state.version != null) {
                val uninstall = index++
                item(key = "uninstall") {
                    SidekitLineButton(
                        "Uninstall app",
                        { onAsk(HubRemoval.Uninstall) },
                        Modifier.height(CompactRowHeight).listFade(list, uninstall),
                        variant = LineButtonVariant.Destructive
                    )
                }
            }
            if (state.mediaTitle != null) {
                val mediaRow = index++
                item(key = "media") {
                    SidekitItem(state.mediaTitle, Modifier.listFade(list, mediaRow), icon = SidekitIcon.MusicNote2, colour = ItemColour.Secondary, showLinkArrow = true, onClick = onPlayer)
                }
            }
            val start = index
            itemsIndexed(state.chats, key = { _, c -> c.key }) { i, c ->
                SidekitItem(c.title, Modifier.listFade(list, start + i), secondary = c.messages.lastOrNull()?.text, onClick = { onChat(c.key) })
            }
            index += state.chats.size
            if (!state.bridgeOn) {
                item(key = "bridge") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, index), onClick = onBridge) {
                        SidekitText("Turn on notification access for Sidekit to read and answer ${app.label}'s chats here and control its music.", style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            } else if (state.chats.isEmpty() && state.mediaTitle == null) {
                item(key = "none") { SidekitItem("No chats or media yet", Modifier.listFade(list, start), colour = ItemColour.Muted) }
            }
        }
    }
}

/**
 * The app itself: its icon, its installed version (or that it is missing) and its package. The
 * screen title above already names the app, so this card carries what the title cannot.
 */
@Composable
private fun SideportHeader(app: PortedApp, version: String?, modifier: Modifier = Modifier) {
    SidekitCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)) {
            PortedAppIcon(app, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (version != null) {
                    SidekitText("Version $version", style = SidekitTheme.typography.body)
                } else {
                    SidekitText("Not installed", style = SidekitTheme.typography.body, color = SidekitTheme.colors.dangerA2)
                }
                SidekitText(app.packageName, style = SidekitTheme.typography.tiny, color = SidekitTheme.colors.surfaceA5)
            }
        }
    }
}

/** A chat read from a ported app's notifications, answered through its inline reply. */
@Composable
internal fun SideportChatScreen(key: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val conversations by Bridge.conversations.collectAsStateWithLifecycle()
    val chat: Conversation? = conversations[key]
    var draft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val canReply = chat?.reply != null
    val actions = buildList {
        add(SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, tone = ActionTone.Muted, motion = ActionMotion.Back))
        if (canReply) {
            add(
                SideAction("send", "Send", SidekitIcon.ArrowUpward, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up) {
                    if (draft.isBlank()) return@SideAction
                    if (Bridge.reply(context, key, draft.trim())) draft = "" else message = "This chat can't be answered from Sidekit any more. Open the app to reply."
                }
            )
        } else {
            chat?.open?.let { open -> add(SideAction.forward("open", "Open chat", { runCatching { open.send() } })) }
        }
    }
    SidekitScaffold(chat?.title ?: "Chat", actions = actions) { padding ->
        SidekitList(padding, state = list) {
            message?.let { text -> item(key = "message") { SidekitCard(Modifier.fillMaxWidth(), onClick = { message = null }) { SidekitText(text, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) } } }
            val messages = chat?.messages.orEmpty()
            itemsIndexed(messages) { _, m ->
                SidekitCard(Modifier.fillMaxWidth(), color = if (m.mine) SidekitTheme.colors.primaryA1 else SidekitTheme.colors.surfaceA3) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.sender?.let { SidekitText(it, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6) }
                        SidekitText(m.text, style = SidekitTheme.typography.bodySmall, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            if (canReply) item(key = "draft") { SidekitTextField(draft, { draft = it }, placeholder = "Reply", focusOnKeypad = true) }
        }
    }
}

/** Transport controls for a ported app's media session. */
@Composable
internal fun SideportPlayerScreen(pkg: String, onBack: () -> Unit) {
    val media by Bridge.media.collectAsStateWithLifecycle()
    val controller: MediaController? = media[pkg]
    var metadata by remember(controller) { mutableStateOf(controller?.metadata) }
    var playback by remember(controller) { mutableStateOf(controller?.playbackState) }
    DisposableEffect(controller) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(m: MediaMetadata?) {
                metadata = m
            }
            override fun onPlaybackStateChanged(s: PlaybackState?) {
                playback = s
            }
        }
        controller?.registerCallback(callback)
        onDispose { controller?.unregisterCallback(callback) }
    }
    val playing = playback?.state == PlaybackState.STATE_PLAYING
    val transport = controller?.transportControls
    val actions = buildList {
        add(SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, tone = ActionTone.Muted, motion = ActionMotion.Back))
        if (transport != null) {
            add(SideAction.square("prev", SidekitIcon.SkipPrevious, "Previous", { transport.skipToPrevious() }, tone = ActionTone.Muted))
            add(
                SideAction(if (playing) "pause" else "play", icon = if (playing) SidekitIcon.Pause else SidekitIcon.PlayArrow, contentDescription = if (playing) "Pause" else "Play") {
                    if (playing) transport.pause() else transport.play()
                }
            )
            add(SideAction.square("next", SidekitIcon.SkipNext, "Next", { transport.skipToNext() }, tone = ActionTone.Muted))
        }
    }
    SidekitScaffold("Now playing", actions = actions) { padding ->
        SidekitList(padding) {
            if (controller == null) {
                item { SidekitItem("Nothing playing", colour = ItemColour.Muted) }
                return@SidekitList
            }
            item {
                SidekitCard(Modifier.fillMaxWidth().height(200.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SidekitText(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown", style = SidekitTheme.typography.headline, maxLines = 2)
                        (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.let {
                            SidekitText(it, style = SidekitTheme.typography.label, color = SidekitTheme.colors.surfaceA6)
                        }
                        metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let {
                            SidekitText(it, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
                        }
                    }
                }
            }
        }
    }
}
