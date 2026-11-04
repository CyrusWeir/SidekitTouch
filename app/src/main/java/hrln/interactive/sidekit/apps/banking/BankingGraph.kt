package hrln.interactive.sidekit.apps.banking

import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
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
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.navigation.SubApp

private val ROOT = SubApp.Banking.route
private val HOME = "$ROOT/home"
private val ACCOUNTS = "$ROOT/accounts"
private const val ARG_ID = "id"
private const val ARG_INCOME = "income"
private val ACCOUNT = "$ROOT/account?$ARG_ID={$ARG_ID}"
private val PAY = "$ROOT/pay?$ARG_INCOME={$ARG_INCOME}"

private fun accountRoute(id: Long) = "$ROOT/account?$ARG_ID=$id"
private fun payRoute(income: Boolean) = "$ROOT/pay?$ARG_INCOME=$income"

fun NavGraphBuilder.bankingGraph(nav: NavHostController) {
    navigation(startDestination = HOME, route = ROOT) {
        composable("$ROOT/up") { entry -> Unlocked(entry, nav) { _, _, _ -> UpBankScreen { nav.popFrom(entry) } } }
        composable(HOME) { entry ->
            Unlocked(entry, nav) { vm, state, book ->
                BankingScreen(
                    account = homeAccount(book),
                    transactions = homeTransactions(book),
                    onAccounts = { nav.navigateFrom(entry, if (book.accounts.isEmpty()) accountRoute(-1) else ACCOUNTS) },
                    onPay = { nav.navigateFrom(entry, payRoute(false)) },
                    onDeleteTransaction = { vm.deleteTransaction(it.id) },
                    undo = state.undo,
                    onUndo = vm::undo,
                    options = listOfNotNull(
                        SideAction("up", "Up · read-only") { nav.navigateFrom(entry, "$ROOT/up") },
                        if (book.accounts.isNotEmpty()) SideAction("income", "Add income", SidekitIcon.Add) { nav.navigateFrom(entry, payRoute(true)) } else null,
                        SideAction("lock", "Lock", SidekitIcon.Encrypted) { vm.lock() },
                    ),
                )
            }
        }
        composable(ACCOUNTS) { entry ->
            Unlocked(entry, nav) { vm, state, book ->
                BankingAccountsScreen(
                    book = book,
                    onBack = { nav.popFrom(entry) },
                    onOpen = { vm.selectAccount(it.id); nav.popFrom(entry) },
                    onNew = { nav.navigateFrom(entry, accountRoute(-1)) },
                    onEdit = { nav.navigateFrom(entry, accountRoute(it.id)) },
                    onDelete = { vm.deleteAccount(it.id) },
                    undo = state.undo,
                    onUndo = vm::undo,
                )
            }
        }
        composable(ACCOUNT, arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType; defaultValue = -1L })) { entry ->
            val id = entry.arguments?.getLong(ARG_ID) ?: -1L
            Unlocked(entry, nav) { vm, _, book ->
                val existing = book.accounts.firstOrNull { it.id == id }
                BankingAccountEditor(
                    existing = existing,
                    onCancel = { nav.popFrom(entry) },
                    onSave = { name, cents ->
                        if (existing != null) vm.editAccount(existing.id, name, cents) else vm.addAccount(name, cents)?.let(vm::selectAccount)
                        nav.popFrom(entry)
                    },
                )
            }
        }
        composable(PAY, arguments = listOf(navArgument(ARG_INCOME) { type = NavType.BoolType; defaultValue = false })) { entry ->
            val income = entry.arguments?.getBoolean(ARG_INCOME) ?: false
            Unlocked(entry, nav) { vm, _, book ->
                BankingPayScreen(
                    book = book,
                    incoming = income,
                    onCancel = { nav.popFrom(entry) },
                    onRecord = { account, payee, cents, note ->
                        vm.record(account, payee, cents, note, income)
                        nav.popFrom(entry)
                    },
                )
            }
        }
    }
}

/**
 * Shows [content] only while Banking is unlocked; otherwise the lock screen (or the "can't read"
 * screen). Banking locks itself whenever the app stops, so every route is gated, not just home.
 */
@Composable
private fun Unlocked(
    entry: NavBackStackEntry,
    nav: NavHostController,
    content: @Composable (BankingViewModel, BankingUiState, BankBook) -> Unit,
) {
    val vm = entry.bankingViewModel(nav)
    val state by vm.state.collectAsStateWithLifecycle()
    OnAppStop { vm.lock() }
    val leave = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack(ROOT, inclusive = true) }
    when (val load = state.load) {
        is BankingRepository.Load.Open -> content(vm, state, load.book)
        BankingRepository.Load.Unreadable -> BankingUnreadableScreen(onBack = { leave() }, onErase = vm::eraseUnreadable)
        else -> LockedRoute(vm, onBack = { leave() })
    }
}

@Composable
private fun LockedRoute(vm: BankingViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val gate = remember(context) { BankingGate(context) }
    val secure = remember(gate) { gate.deviceSecure }
    var message by remember { mutableStateOf<String?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    // Ask once on arrival; a cancelled prompt waits for "Unlock" rather than popping up again.
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = {
        if (!secure) {
            vm.unlock()
        } else if (signal == null) {
            message = null
            signal = gate.authenticate(
                onSuccess = { signal = null; vm.unlock() },
                onFailure = { error -> signal = null; message = error },
            )
        }
    }
    LaunchedEffect(Unit) {
        if (secure && !asked && !inspection) {
            asked = true
            ask()
        }
    }
    DisposableEffect(Unit) { onDispose { signal?.cancel() } }
    BankingLockedScreen(deviceSecure = secure, message = message, onUnlock = ask, onBack = onBack)
}

private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry?.id == entry.id) navigate(route) { launchSingleTop = true }
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** One ViewModel per visit to Banking, shared by its screens. Leaving clears it, which locks. */
@Composable
private fun NavBackStackEntry.bankingViewModel(nav: NavHostController): BankingViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    return viewModel(parent, factory = remember(context) { viewModelFactory { initializer { BankingViewModel(Banking.repository(context)) } } })
}
