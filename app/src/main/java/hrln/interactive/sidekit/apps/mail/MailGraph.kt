package hrln.interactive.sidekit.apps.mail

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitSwipeRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.components.rememberSwipeRevealGroup
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Mail.route
private val HOME = "$ROOT/home"
private val FOLDERS = "$ROOT/folders"
private val FOLDER = "$ROOT/folder/{folder}"
private val COMPOSE = "$ROOT/compose?id={id}"
object MailTags { const val TO = "mail_to"; const val SUBJECT = "mail_subject"; const val BODY = "mail_body" }

fun NavGraphBuilder.mailGraph(nav: NavHostController) {
    navigation(startDestination = HOME, route = ROOT) {
        composable(HOME) {
            val vm = mailViewModel()
            val messages by vm.messages.collectAsStateWithLifecycle()
            val notice by vm.notice.collectAsStateWithLifecycle()
            val undo by vm.undo.collectAsStateWithLifecycle()
            val actions = mailActions(notice, vm::dismissNotice, undo != null, vm::undo,
                SideAction.back("folders", "Folders") { nav.navigate(FOLDERS) },
                SideAction.forward("compose", "Compose", onClick = { nav.navigate("$ROOT/compose") }),
            )
            SidekitScaffold(title = "Mail", actions = actions) { padding ->
                MessageList(padding, messages, null, vm::delete) { nav.navigate("$ROOT/compose?id=${it.id}") }
            }
        }
        composable(FOLDERS) {
            val vm = mailViewModel()
            val messages by vm.messages.collectAsStateWithLifecycle()
            SidekitScaffold(title = "Mail folders", actions = listOf(SideAction.back("back", "Mail") { nav.popBackStack() })) { padding ->
                val state = rememberLazyListState()
                SidekitList(padding, state = state) {
                    val folders = listOf(MailFolder.Draft, MailFolder.Outbox, MailFolder.HandedOff)
                    itemsIndexed(folders, key = { _, it -> it.name }) { index, folder ->
                        val count = messages.orEmpty().count { it.folder == folder }
                        SidekitItem(folder.label, Modifier.listFade(state, index), secondary = "$count on this phone", icon = SidekitIcon.FolderCopy, showLinkArrow = true) {
                            nav.navigate("$ROOT/folder/${folder.name}")
                        }
                    }
                }
            }
        }
        composable(FOLDER) { entry ->
            val vm = mailViewModel()
            val messages by vm.messages.collectAsStateWithLifecycle()
            val notice by vm.notice.collectAsStateWithLifecycle()
            val undo by vm.undo.collectAsStateWithLifecycle()
            val folder = MailFolder.valueOf(entry.arguments?.getString("folder") ?: MailFolder.Draft.name)
            val actions = mailActions(notice, vm::dismissNotice, undo != null, vm::undo,
                SideAction.back("back", "Folders") { nav.popBackStack() },
                SideAction.forward("compose", "Compose", onClick = { nav.navigate("$ROOT/compose") }),
            )
            SidekitScaffold(title = folder.label, actions = actions) { padding ->
                MessageList(padding, messages, folder, vm::delete) { nav.navigate("$ROOT/compose?id=${it.id}") }
            }
        }
        composable(COMPOSE, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })) { entry ->
            val vm = mailViewModel()
            val messages by vm.messages.collectAsStateWithLifecycle()
            val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
            val existing = messages.orEmpty().firstOrNull { it.id == id }
            if (id != null && messages == null) return@composable
            ComposeScreen(existing, onCancel = { nav.popBackStack() }, onSave = { to, subject, body ->
                vm.saveDraft(id, to, subject, body); nav.popBackStack()
            }, onSend = { to, subject, body ->
                vm.send(id, to, subject, body); nav.popBackStack(HOME, inclusive = false)
            })
        }
    }
}

@Composable
private fun mailViewModel(): MailViewModel {
    val activity = LocalActivity.current as ComponentActivity
    val context = activity.applicationContext
    val factory = remember(context) { viewModelFactory { initializer { MailViewModel(Mail.repository(context), IntentMailAccount(activity)) } } }
    return viewModel(activity, factory = factory)
}

private fun mailActions(
    notice: String?, dismiss: () -> Unit, hasUndo: Boolean, undo: () -> Unit, first: SideAction, second: SideAction,
): List<SideAction> = when {
    notice != null -> listOf(SideAction("notice", notice, tone = ActionTone.Muted, dense = true, onClick = dismiss))
    hasUndo -> listOf(SideAction("undo", "Undo", SidekitIcon.Undo, tone = ActionTone.Muted, motion = ActionMotion.Up, onClick = undo), second)
    else -> listOf(first, second)
}

@Composable
private fun MessageList(
    padding: PaddingValues,
    messages: List<MailMessage>?,
    folder: MailFolder?,
    onDelete: (Long) -> Unit,
    onOpen: (MailMessage) -> Unit,
) {
    val shown = messages.orEmpty().filter { folder == null || it.folder == folder }
    val state = rememberLazyListState()
    val swipe = rememberSwipeRevealGroup()
    SidekitList(padding, state = state) {
        item(key = "notice") {
            SidekitCard(Modifier.fillMaxWidth().listFade(state, 0), color = SidekitTheme.colors.surfaceA2) {
                SidekitText("Local drafts only · no inbox or account connected", style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
            }
        }
        if (messages != null && shown.isEmpty()) item(key = "empty") {
            SidekitItem(if (folder == null) "No local messages" else "No ${folder.label.lowercase()}", Modifier.listFade(state, 1), secondary = "Compose creates a draft on this phone")
        }
        itemsIndexed(shown, key = { _, it -> it.id }) { index, message ->
            SidekitSwipeRow(key = message.id, group = swipe, onDelete = { onDelete(message.id) }, modifier = Modifier.animateItem().listFade(state, index + 1), deleteLabel = "Delete message") {
                SidekitItem(message.to.ifEmpty { "No recipient" }, secondary = "${message.folder.label} · ${message.subject.ifEmpty { "No subject" }}", onClick = { onOpen(message) })
            }
        }
    }
}

@Composable
private fun ComposeScreen(
    existing: MailMessage?,
    onCancel: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onSend: (String, String, String) -> Unit,
) {
    var to by remember(existing?.id) { mutableStateOf(existing?.to.orEmpty()) }
    var subject by remember(existing?.id) { mutableStateOf(existing?.subject.orEmpty()) }
    var body by remember(existing?.id) { mutableStateOf(existing?.body.orEmpty()) }
    BackHandler(onBack = onCancel)
    SidekitScaffold(title = if (existing == null) "Compose" else existing.folder.label, actions = listOf(
        SideAction.back("cancel", "Cancel", onCancel),
        SideAction("save", "Save", tone = ActionTone.Muted) { onSave(to, subject, body) },
        SideAction.forward("send", "Send", onClick = { onSend(to, subject, body) }),
    )) { padding ->
        val state = rememberLazyListState()
        SidekitList(padding, state = state) {
            item(key = "to") { MailField("To", to, { to = it }, Modifier.listFade(state, 0).testTag(MailTags.TO), true, true) }
            item(key = "subject") { MailField("Subject", subject, { subject = it }, Modifier.listFade(state, 1).testTag(MailTags.SUBJECT), true, false) }
            item(key = "body") { MailField("Message", body, { body = it }, Modifier.listFade(state, 2).testTag(MailTags.BODY), false, false) }
            item(key = "handoff") {
                SidekitText("Send opens your mail app. Sidekit cannot confirm delivery.", Modifier.listFade(state, 3), style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

@Composable
private fun MailField(
    placeholder: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier,
    singleLine: Boolean,
    autoFocus: Boolean,
) {
    SidekitCard(modifier.fillMaxWidth().heightIn(min = SidekitTheme.spacing.itemHeight), color = SidekitTheme.colors.surfaceA2) {
        SidekitTextField(value, onValue, Modifier.fillMaxWidth(), placeholder = placeholder, singleLine = singleLine, focusOnKeypad = autoFocus, autoFocus = autoFocus)
    }
}
