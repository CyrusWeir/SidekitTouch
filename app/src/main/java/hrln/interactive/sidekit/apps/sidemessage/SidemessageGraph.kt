package hrln.interactive.sidekit.apps.sidemessage

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.apps.phonebook.Phonebook
import hrln.interactive.sidekit.apps.phonebook.PhoneFormat
import hrln.interactive.sidekit.apps.phonebook.PhonebookRoutes
import hrln.interactive.sidekit.apps.phonebook.rememberPermissionGate
import hrln.interactive.sidekit.navigation.LAUNCHER_ROUTE
import hrln.interactive.sidekit.navigation.SubApp
import kotlinx.coroutines.flow.MutableStateFlow

private val ROOT = SubApp.Sidemessage.route
private val INBOX = "$ROOT/inbox"
private val NEW = "$ROOT/new"
private const val ARG_TO = "to"
private const val ARG_THREAD = "thread"
private const val ARG_BODY = "body"
private val THREAD = "$ROOT/thread?$ARG_TO={$ARG_TO}&$ARG_THREAD={$ARG_THREAD}&$ARG_BODY={$ARG_BODY}"

object SidemessageRoutes {
    /** A conversation with [addresses]. [threadId] if known (else found by address); [body] prefills the draft. */
    fun conversation(addresses: List<String>, threadId: Long = -1L, body: String? = null): String =
        "$ROOT/thread?$ARG_TO=${Uri.encode(addresses.joinToString(";"))}&$ARG_THREAD=$threadId" +
            (body?.let { "&$ARG_BODY=${Uri.encode(it)}" } ?: "")

    val newMessage: String get() = NEW
}

fun NavGraphBuilder.sidemessageGraph(nav: NavHostController) {
    navigation(startDestination = INBOX, route = ROOT) {
        composable(INBOX) { entry ->
            val vm = entry.sidemessageViewModel(nav)
            val access = rememberSmsAccess()
            val contacts = rememberPermissionGate(Manifest.permission.READ_CONTACTS)
            LaunchedEffect(contacts.granted) { vm.setContactsAccess(contacts.granted) }
            LaunchedEffect(access.canRead, access.isDefault) { vm.setAccess(access.canRead, access.isDefault) }
            val state by vm.inbox.collectAsStateWithLifecycle()
            SidemessageInboxScreen(
                state = state,
                canBecomeDefault = access.roleAvailable,
                onAllow = access::requestRead,
                contactsAllowed = contacts.granted,
                onAllowContacts = contacts::request,
                onMakeDefault = access::requestDefault,
                onOpen = { nav.navigateFrom(entry, SidemessageRoutes.conversation(it.addresses, it.id)) },
                onOpenPinned = { nav.navigateFrom(entry, SidemessageRoutes.conversation(listOf(it.address), it.thread?.id ?: -1L)) },
                onNewMessage = { nav.navigateFrom(entry, NEW) },
            )
        }
        composable(NEW) { entry ->
            val vm = entry.sidemessageViewModel(nav)
            val contacts = rememberPermissionGate(Manifest.permission.READ_CONTACTS)
            LaunchedEffect(contacts.granted) { vm.setContactsAccess(contacts.granted) }
            val state by vm.newMessage.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val region = remember { PhoneFormat.region(context) }
            NewMessageScreen(
                state = state,
                region = region,
                onQuery = vm::setQuery,
                onPick = { number ->
                    vm.setQuery("")
                    // Replace New Message with the conversation, so Back returns to the inbox.
                    if (nav.currentBackStackEntry?.id == entry.id) nav.navigate(SidemessageRoutes.conversation(listOf(number))) { popUpTo(NEW) { inclusive = true } }
                },
                onBack = { nav.popFrom(entry) },
                onAllowContacts = contacts::request,
            )
        }
        composable(
            THREAD,
            arguments = listOf(
                navArgument(ARG_TO) { type = NavType.StringType; defaultValue = "" },
                navArgument(ARG_THREAD) { type = NavType.LongType; defaultValue = -1L },
                navArgument(ARG_BODY) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val context = LocalContext.current
            val args = entry.arguments
            val addresses = remember(entry) { args?.getString(ARG_TO).orEmpty().split(';').filter { it.isNotBlank() } }
            val threadArg = args?.getLong(ARG_THREAD) ?: -1L
            val body = args?.getString(ARG_BODY)
            val repo = remember { Sidemessage.repository(context) }
            val vm: ConversationViewModel = viewModel(
                entry,
                factory = remember(entry) { viewModelFactory { initializer { ConversationViewModel(repo, addresses, threadArg, body) } } },
            )
            val access = rememberSmsAccess()
            LaunchedEffect(access.canRead, access.isDefault) { vm.setAccess(access.canRead, access.isDefault) }
            val state by vm.state.collectAsStateWithLifecycle()
            val threadId by vm.threadId.collectAsState()

            // While this conversation is on screen its messages don't notify, and opening it clears them.
            LifecycleStartEffect(addresses) {
                val keys = addresses.map(Addresses::key).toSet()
                repo.visible = keys
                onStopOrDispose { if (repo.visible === keys) repo.visible = null }
            }
            LaunchedEffect(threadId) { SidemessageNotifications.cancel(context, threadId, addresses) }

            // Sending asks for SMS permission at the moment it's first needed.
            var sendAfterGrant by remember { mutableStateOf(false) }
            val sendGate = rememberPermissionGate(Manifest.permission.SEND_SMS) { ok -> if (ok && sendAfterGrant) vm.send(); sendAfterGrant = false }

            val leave = { vm.stopTyping(); nav.popFrom(entry) }
            BackHandler(enabled = state.draft != null, onBack = vm::stopTyping)
            ConversationScreen(
                state = state,
                canBecomeDefault = access.roleAvailable,
                onBack = leave,
                onAllow = access::requestRead,
                onStartTyping = vm::startTyping,
                onDraftChange = vm::updateDraft,
                onSend = {
                    if (sendGate.granted) vm.send() else { sendAfterGrant = true; sendGate.request() }
                },
                onRetry = vm::retry,
                onLoadOlder = vm::loadOlder,
                onLoadLatest = vm::loadLatest,
                onTogglePin = if (addresses.size == 1) vm::togglePin else null,
                onCall = addresses.singleOrNull()?.let { number -> { nav.navigateFrom(entry, PhonebookRoutes.dial(number)) } },
                onMakeDefault = access::requestDefault,
            )
        }
    }
}

/**
 * Sidemessage's access as screen state: SMS permission (re-checked on resume) and whether Sidekit
 * holds the default SMS role. [requestDefault] asks for the role with the system's own dialog.
 */
@Stable
class SmsAccessState {
    var canRead by mutableStateOf(false)
        internal set
    var isDefault by mutableStateOf(false)
        internal set
    var roleAvailable by mutableStateOf(false)
        internal set
    internal var onRequestRead: () -> Unit = {}
    internal var onRequestDefault: () -> Unit = {}

    fun requestRead() = onRequestRead()
    fun requestDefault() = onRequestDefault()
}

@Composable
fun rememberSmsAccess(): SmsAccessState {
    val context = LocalContext.current
    val state = remember { SmsAccessState() }
    val read = rememberPermissionGate(Manifest.permission.READ_SMS)
    val notifications = if (Build.VERSION.SDK_INT >= 33) rememberPermissionGate(Manifest.permission.POST_NOTIFICATIONS) else null
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.isDefault = SmsRole.isDefault(context)
        // The default app posts message notifications; Android 13+ asks for that separately.
        if (state.isDefault && notifications?.granted == false) notifications.request()
    }
    LifecycleResumeEffect(state) {
        state.isDefault = SmsRole.isDefault(context)
        state.roleAvailable = SmsRole.isAvailable(context)
        onPauseOrDispose { }
    }
    // The role grants the SMS permissions, so holding it counts as having them.
    state.canRead = read.granted || state.isDefault
    state.onRequestRead = read::request
    state.onRequestDefault = {
        val intent = SmsRole.requestIntent(context)
        if (intent != null) role.launch(intent)
    }
    return state
}

/**
 * Routes from outside the app into Sidemessage: `sms:`/`smsto:` links from other apps (SENDTO) and
 * taps on message notifications. [MainActivity][hrln.interactive.sidekit.MainActivity] hands them
 * to [handle]; the nav host follows with [Follow].
 */
object IncomingLinks {
    const val ACTION_OPEN_THREAD = "hrln.interactive.sidekit.action.OPEN_THREAD"
    const val EXTRA_THREAD = "thread"
    const val EXTRA_ADDRESSES = "addresses"
    private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    private val pending = MutableStateFlow<String?>(null)

    /** The route an intent asks for, or null if it isn't one of ours. */
    fun routeFor(intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            ACTION_OPEN_THREAD -> {
                val addresses = intent.getStringArrayExtra(EXTRA_ADDRESSES)?.toList().orEmpty()
                if (addresses.isEmpty()) null else SidemessageRoutes.conversation(addresses, intent.getLongExtra(EXTRA_THREAD, -1L))
            }
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme?.lowercase() !in SMS_SCHEMES) return null
                val addresses = Addresses.fromUri(data)
                val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: data.encodedSchemeSpecificPart?.substringAfter('?', "")?.split('&')
                        ?.firstOrNull { it.substringBefore('=') == "body" }?.substringAfter('=')?.let(Uri::decode)
                if (addresses.isEmpty()) NEW else SidemessageRoutes.conversation(addresses, body = body)
            }
            else -> null
        }
    }

    /** Called by the activity with each launch intent. Returns true if it was a Sidemessage link. */
    fun handle(intent: Intent?): Boolean {
        val route = routeFor(intent) ?: return false
        pending.value = route
        return true
    }

    /** Navigates to a pending link: launcher, then the Sidemessage inbox, then the conversation. */
    @Composable
    fun Follow(nav: NavHostController) {
        val route by pending.collectAsState()
        LaunchedEffect(route) {
            val target = route ?: return@LaunchedEffect
            pending.value = null
            nav.navigate(ROOT) { popUpTo(LAUNCHER_ROUTE) }
            if (target != ROOT && target != INBOX) nav.navigate(target)
        }
    }
}

/** Ignores repeat taps that land while a transition is already under way. */
private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id == entry.id) navigate(route) { launchSingleTop = true }
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** One ViewModel per visit to the app, shared by the inbox and New Message. */
@Composable
private fun NavBackStackEntry.sidemessageViewModel(nav: NavHostController): SidemessageViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(
        parent,
        factory = remember(context) {
            viewModelFactory { initializer { SidemessageViewModel(Sidemessage.repository(context), Phonebook.source(context)) } }
        },
    )
}
