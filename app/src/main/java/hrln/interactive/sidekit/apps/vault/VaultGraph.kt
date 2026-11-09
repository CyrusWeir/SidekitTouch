package hrln.interactive.sidekit.apps.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.design.theme.SidekitTheme

fun NavGraphBuilder.vaultGraph(nav: NavHostController) {
    composable(SubApp.Vault.route) {
        val context = LocalContext.current
        val vm: VaultViewModel = viewModel(factory = remember { viewModelFactory { initializer { VaultViewModel(VaultRepository.create(context.applicationContext)) } } })
        VaultScreen(vm) { nav.popBackStack() }
    }
}

@Composable
internal fun VaultScreen(vm: VaultViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first() }
    val state by vm.state.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }
    var confirmDelete by remember { mutableStateOf<PrivateNote?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val keypad = hrln.interactive.sidekit.design.input.LocalKeypad.current
    LaunchedEffect(state.unlocked) {
        if (!state.unlocked) { confirmDelete = null; confirmDiscard = false; keypad?.finishTyping() }
    }
    LifecycleStartEffect(vm) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onStopOrDispose {
            prompt?.cancel(); prompt = null
            vm.lock(); confirmDelete = null; confirmDiscard = false
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    fun unlock() {
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            vm.error("Set a screen lock in Android Settings before using Vault."); return
        }
        if (prompt != null) return
        val signal = CancellationSignal()
        prompt = signal
        BiometricPrompt.Builder(context).setTitle("Unlock Vault")
            .setSubtitle("Private notes · locks after 55 seconds")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build().authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (prompt !== signal) return
                    prompt = null; vm.authenticated()
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (prompt !== signal) return
                    prompt = null; vm.error(message.toString())
                }
            })
    }
    fun leaveEditor() {
        val edited = state.editing ?: return
        if (state.notes.firstOrNull { it.id == edited.id }?.body.orEmpty() != edited.body) confirmDiscard = true
        else vm.closeEditor()
    }
    BackHandler { if (state.editing != null) leaveEditor() else { vm.lock(); back() } }
    val actions = when {
        state.busy -> listOf(SideAction("save", "Working…") {})
        !state.unlocked -> listOf(SideAction.back("back", "Home", back), SideAction("unlock", "Unlock", onClick = ::unlock))
        confirmDelete != null -> listOf(SideAction("back", "Cancel") { confirmDelete = null }, SideAction("delete", "Delete", tone = ActionTone.Destructive) { confirmDelete?.let(vm::delete); confirmDelete = null })
        confirmDiscard -> listOf(SideAction("back", "Keep editing") { confirmDiscard = false }, SideAction("discard", "Discard", tone = ActionTone.Destructive) { vm.closeEditor(); confirmDiscard = false })
        state.editing != null -> listOf(SideAction.back("back", "Notes", ::leaveEditor), SideAction("save", "Save", tone = ActionTone.Secondary, onClick = vm::save))
        else -> listOf(SideAction("lock", "Lock", onClick = vm::lock), SideAction.forward("new", "New note", { vm.edit() }))
    }
    val list = rememberLazyListState()
    val swipe = rememberSwipeRevealGroup()
    SidekitScaffold("Vault", actions = actions, options = if (state.editing != null) listOf(SideAction("delete", "Delete") { confirmDelete = state.editing }) else emptyList()) { padding ->
        SidekitList(padding, state = list) {
            state.message?.let { item { SidekitCard(Modifier.listFade(list, 0)) { SidekitText(it, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) } } }
            when {
                state.busy -> item { SidekitItem("Working…") }
                !state.unlocked -> item { SidekitCard(Modifier.listFade(list, 1)) { SidekitText("Protected by your device lock. Save before leaving: Vault locks after 55 seconds or when you leave. Unsaved edits are discarded. Private keypad uses abc without saving learned words.", style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE) } }
                state.editing != null -> item {
                    VaultEditor(state.editing!!, vm::text)
                }
                else -> {
                    if (state.notes.isEmpty()) item { SidekitItem("No private notes yet", Modifier.listFade(list, 1)) }
                    itemsIndexed(state.notes, key = { _, note -> note.id }) { index, note ->
                        SidekitSwipeRow(note.id, swipe, onDelete = { confirmDelete = note }, modifier = Modifier.listFade(list, index)) {
                            SidekitItem(note.title, onClick = { vm.edit(note) })
                        }
                    }
                }
            }
        }
    }
}

/** Never use rememberTextFieldState here: its saver would put plaintext in activity saved state. */
@Composable
private fun VaultEditor(note: PrivateNote, onText: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val globalKeypad = hrln.interactive.sidekit.design.input.LocalKeypad.current
    val text = remember(note.id) { androidx.compose.foundation.text.input.TextFieldState(note.body) }
    // This existing factory has neither preferences nor a user-word file. Learning stays in RAM.
    val privateKeypad = remember(note.id) {
        hrln.interactive.sidekit.design.input.Keypad.forTesting(scope, hrln.interactive.sidekit.design.input.T9Words.Empty,
            globalKeypad?.module ?: hrln.interactive.sidekit.design.input.KeypadModule.T9,
            // The same custom key mapping as everywhere else, so remapped keys type here too.
            mapper = globalKeypad?.mapper ?: hrln.interactive.sidekit.design.input.KeyMapper.forTesting()).also {
            it.engine.setMode(hrln.interactive.sidekit.design.input.T9Mode.MultiTap,
                object : hrln.interactive.sidekit.design.input.T9Buffer {
                    override val text: CharSequence = ""
                    override val selectionStart = 0
                    override val selectionEnd = 0
                    override fun replace(start: Int, end: Int, text: String, selectStart: Int, selectEnd: Int) {}
                })
        }
    }
    val latest by rememberUpdatedState(onText)
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { latest(it) } }
    CompositionLocalProvider(hrln.interactive.sidekit.design.input.LocalKeypad provides privateKeypad) {
        SidekitCard { SidekitTextField(text, placeholder = "Private note…", focusOnKeypad = true, autoFocus = true, dictation = false) }
    }
}
