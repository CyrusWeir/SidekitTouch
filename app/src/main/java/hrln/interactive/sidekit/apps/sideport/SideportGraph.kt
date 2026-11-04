package hrln.interactive.sidekit.apps.sideport

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.apps.apps.AppsRepository
import hrln.interactive.sidekit.apps.apps.matchesApp
import hrln.interactive.sidekit.apps.sideport.bridge.Bridge
import hrln.interactive.sidekit.apps.sideport.bridge.Conversation
import hrln.interactive.sidekit.apps.sideport.install.ApkInstaller
import hrln.interactive.sidekit.apps.sideport.install.InstallStatus
import hrln.interactive.sidekit.apps.sideport.registry.PortMode
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.apps.sideport.service.SideportService
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
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
                onHub = { nav.navigate(SideportRoutes.hub(it)) { launchSingleTop = true } },
            )
        }
        composable(SideportRoutes.ADD) { SideportAddScreen(onDone = { nav.popBackStack() }) }
        composable(SideportRoutes.HUB) { entry ->
            val pkg = entry.arguments?.getString("pkg").orEmpty()
            SideportHubScreen(
                pkg,
                onBack = { nav.popBackStack() },
                onChat = { nav.navigate(SideportRoutes.chat(it)) },
                onPlayer = { nav.navigate(SideportRoutes.player(pkg)) },
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
    OpenResult.NotInstalled -> "${app.label} isn't installed any more. Remove it from Sideport with Options."
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
        actions = listOf(SideAction.back("home", "Home", onHome), SideAction.forward("add", "Add app", onAdd)),
    ) { padding ->
        SidekitList(padding, state = list) {
            var index = 0
            (message ?: installMessage(install))?.let { text ->
                val i = index++
                item(key = "message") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, i), onClick = { message = null; ApkInstaller.clear() }) {
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
                            maxLines = Int.MAX_VALUE,
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
                        onLongClickLabel = "${app.label} messages, media and options",
                    ),
                    secondary = if (app.mode == PortMode.Original) "Original" else null,
                    icon = SidekitIcon.DeployedCode,
                )
            }
            index += apps.size
            val setup = index
            item(key = "projection") {
                SidekitItem(
                    "Projection", Modifier.listFade(list, setup), colour = ItemColour.Muted,
                    secondary = if (projection) "On" else "Turn on",
                    onClick = { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                )
            }
            item(key = "bridge") {
                SidekitItem(
                    "Chats & media", Modifier.listFade(list, setup + 1), colour = ItemColour.Muted,
                    secondary = if (bridge) "On" else "Turn on",
                    onClick = { context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
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
    val candidates = remember(installed, portedApps, query) {
        val have = portedApps.map { it.packageName }.toSet()
        installed.filter { it.component.packageName != context.packageName && it.component.packageName !in have && matchesApp(it.label, query) }
            .distinctBy { it.component.packageName }
    }
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
            item(key = "search") { SidekitTextField(query, { query = it }, placeholder = "Search installed apps", singleLine = true, focusOnKeypad = true) }
            itemsIndexed(candidates, key = { _, a -> a.key }) { i, app ->
                SidekitItem(app.label, Modifier.listFade(list, i + 2), showLinkArrow = true, onClick = {
                    ported.add(app.component.packageName, app.label)
                    onDone()
                })
            }
        }
    }
}

/** A ported app's home in Sidekit: open it, its chats and media, and its options. */
@Composable
internal fun SideportHubScreen(pkg: String, onBack: () -> Unit, onChat: (String) -> Unit, onPlayer: () -> Unit) {
    val context = LocalContext.current
    val ported = PortedApps.get(context)
    val apps by ported.apps.collectAsStateWithLifecycle()
    val app = apps.firstOrNull { it.packageName == pkg }
    val conversations by Bridge.conversations.collectAsStateWithLifecycle()
    val media by Bridge.media.collectAsStateWithLifecycle()
    val bridgeOn by Bridge.connected.collectAsStateWithLifecycle()
    val chats = remember(conversations, pkg) { Bridge.conversationsFor(pkg) }
    var message by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    if (app == null) {
        SidekitScaffold("Sideport", actions = listOf(SideAction.back("back", "Back", onBack))) { padding ->
            SidekitList(padding) { item { SidekitItem("This app was removed") } }
        }
        return
    }
    SidekitScaffold(
        app.label,
        actions = listOf(
            SideAction.square("back", SidekitIcon.ArrowLeftAlt, "Back", onBack, tone = ActionTone.Muted, motion = ActionMotion.Back),
            SideAction.forward("open", "Open", { message = openPorted(context, app) }),
        ),
        options = listOf(
            SideAction("mode", if (app.mode == PortMode.Projected) "Use original" else "Use Sidekit") {
                ported.setMode(pkg, if (app.mode == PortMode.Projected) PortMode.Original else PortMode.Projected)
            },
            SideAction("remove", "Remove", tone = ActionTone.Destructive) { ported.remove(pkg); onBack() },
            SideAction("uninstall", "Uninstall", tone = ActionTone.Destructive) {
                context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
    ) { padding ->
        SidekitList(padding, state = list) {
            var index = 0
            message?.let { text ->
                val i = index++
                item(key = "message") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, i), onClick = { message = null }) {
                        SidekitText(text, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            media[pkg]?.let { controller ->
                val i = index++
                item(key = "media") {
                    val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing"
                    SidekitItem(title, Modifier.listFade(list, i), icon = SidekitIcon.MusicNote2, colour = ItemColour.Secondary, showLinkArrow = true, onClick = onPlayer)
                }
            }
            val start = index
            itemsIndexed(chats, key = { _, c -> c.key }) { i, c ->
                SidekitItem(c.title, Modifier.listFade(list, start + i), secondary = c.messages.lastOrNull()?.text, onClick = { onChat(c.key) })
            }
            if (!bridgeOn) {
                item(key = "bridge") {
                    SidekitCard(Modifier.fillMaxWidth().listFade(list, start + chats.size), onClick = { context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) {
                        SidekitText("Turn on notification access for Sidekit to read and answer ${app.label}'s chats here and control its music.", style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                    }
                }
            } else if (chats.isEmpty() && media[pkg] == null) {
                item(key = "none") { SidekitItem("No chats or media yet", Modifier.listFade(list, start), colour = ItemColour.Muted) }
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
        if (canReply) add(SideAction("send", "Send", SidekitIcon.ArrowUpward, iconAtEnd = true, tone = ActionTone.Secondary, motion = ActionMotion.Up) {
            if (draft.isBlank()) return@SideAction
            if (Bridge.reply(context, key, draft.trim())) draft = "" else message = "This chat can't be answered from Sidekit any more. Open the app to reply."
        })
        else chat?.open?.let { open -> add(SideAction.forward("open", "Open chat", { runCatching { open.send() } })) }
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
            override fun onMetadataChanged(m: MediaMetadata?) { metadata = m }
            override fun onPlaybackStateChanged(s: PlaybackState?) { playback = s }
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
            add(SideAction(if (playing) "pause" else "play", icon = if (playing) SidekitIcon.Pause else SidekitIcon.PlayArrow, contentDescription = if (playing) "Pause" else "Play") {
                if (playing) transport.pause() else transport.play()
            })
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
