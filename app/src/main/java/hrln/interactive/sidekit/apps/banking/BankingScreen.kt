package hrln.interactive.sidekit.apps.banking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class BankAccount(val name: String, val balance: String, val caption: String)

data class BankTransaction(val description: String, val amount: String)

object BankingSampleData {
    // Placeholder content from "Banking / Home" (96:959).
    val account = BankAccount("Everyday · 4821", "$2,480.50", "Available balance")
    val transactions = listOf(
        BankTransaction("Groceries", "−$64.20"),
        BankTransaction("Salary", "+$2,150.00"),
    )
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.bankingGraph(nav: NavHostController) {
    composable(SubApp.Banking.route) { BankingScreen(BankingSampleData.account, BankingSampleData.transactions) }
}

/** "Banking / Home" (96:959). "← Accounts" and "Pay →" have no mocked destination. */
@Composable
fun BankingScreen(
    account: BankAccount,
    transactions: List<BankTransaction>,
    onAccounts: () -> Unit = {},
    onPay: () -> Unit = {},
) {
    SidekitScaffold(
        title = "Banking",
        actions = dualArrowActions("Accounts", "Pay", onAccounts, onPay),
    ) { padding ->
        EverydayContent(padding) {
            BalanceCard(account)
            transactions.forEach { SidekitItem(it.description, secondary = it.amount) }
        }
    }
}

/** "Everyday account balance" (96:1012): 171 high, texts at y 18, 58 and 137. */
@Composable
private fun BalanceCard(account: BankAccount) {
    val colors = SidekitTheme.colors
    SidekitTile(Modifier.fillMaxWidth().height(171.dp), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.padding(start = SidekitTheme.spacing.itemPadding, end = SidekitTheme.spacing.itemPadding, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SidekitText(account.name, style = SidekitTheme.typography.caption, color = colors.surfaceA6)
            SidekitText(account.balance, style = SidekitTheme.typography.numeric)
            SidekitText(account.caption, style = SidekitTheme.typography.caption, color = colors.surfaceA6)
        }
    }
}

@SidephonePreview
@Composable
private fun BankingPreview() = SidekitPreviewFrame { BankingScreen(BankingSampleData.account, BankingSampleData.transactions) }
