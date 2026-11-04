package hrln.interactive.sidekit.apps.banking

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import hrln.interactive.sidekit.apps.integrations.PrivateSettings
import hrln.interactive.sidekit.apps.integrations.SecretField
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.math.BigDecimal

@Composable
fun UpBankScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { PrivateSettings(context, "up_connection") }
    var token by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<UpSnapshot?>(null) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("Personal Up API · read-only. Connect to read balances and transactions. Payments are made in your bank's app.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { settings.read() }.optJSONObject("snapshot")?.let { snapshot = UpSnapshot.parse(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "Saved Up connection cannot be read. Reconnect to replace it." }
    }
    fun sync(more: Boolean = false) {
        if (busy) return
        if (more && token.isNotBlank()) { notice = "Refresh to connect the new token before loading older transactions"; return }
        val newToken = token.trim()
        busy = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { if (newToken.isNotBlank()) JSONObject() else settings.read() }
                val credential = newToken.ifBlank { saved.optString("token") }
                val connector = UpBankConnector(credential)
                val next = if (more && snapshot != null) connector.more(snapshot!!) else connector.sync()
                withContext(Dispatchers.IO) { settings.write(JSONObject().put("token", credential).put("snapshot", next.json())) }
                snapshot = next; if (!more) accountId = null; token = ""; notice = "Up read-only · no payments or transfers"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Up could not refresh. Check token and network. The previous snapshot is unchanged." }
            finally { busy = false }
        }
    }
    SidekitScaffold("Up banking", actions = listOf(SideAction.back("back", "Back", onBack), SideAction("sync", if (busy) "Loading…" else if (snapshot == null) "Connect" else "Refresh") { sync() }), options = listOf(SideAction("disconnect", "Disconnect Up") {
        if (!busy) { settings.clear(); snapshot = null; token = ""; notice = "Up disconnected; saved token and bank snapshot deleted" }
    })) { padding ->
        SidekitList(padding) {
            item { UpInfo("Up · read-only", notice) }
            item { SecretField(token, { token = it }, if (snapshot == null) "Personal access token" else "New token (blank keeps saved)") }
            snapshot?.let { data ->
                item { UpInfo("Last synced", java.time.Instant.ofEpochMilli(data.at).atZone(java.time.ZoneId.systemDefault()).toString()) }
                items(data.accounts.size) { i -> val a = data.accounts[i]
                    SidekitItem(a.name, secondary = "${a.currency} ${BigDecimal.valueOf(a.cents, 2)}", onClick = { accountId = if (accountId == a.id) null else a.id })
                }
                val transactions = data.transactions.filter { accountId == null || it.accountId == accountId }
                if (transactions.isEmpty()) item { SidekitItem("No transactions in this page") }
                items(transactions.size) { i -> val t = transactions[i]
                    UpInfo(t.description, "${t.currency} ${BigDecimal.valueOf(t.cents, 2)} · ${t.status}\n${t.createdAt}")
                }
                if (data.next != null) item { SidekitItem("Older transactions", secondary = if (busy) "Loading…" else "Load next page", onClick = { sync(true) }) }
            }
        }
    }
}

@Composable
private fun UpInfo(title: String, secondary: String) {
    SidekitCard(Modifier.fillMaxWidth()) {
        Column {
            SidekitText(title, maxLines = Int.MAX_VALUE)
            SidekitText(secondary, maxLines = Int.MAX_VALUE, style = hrln.interactive.sidekit.design.theme.SidekitTheme.typography.small)
        }
    }
}
