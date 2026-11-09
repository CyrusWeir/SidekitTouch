package hrln.interactive.sidekit.apps.mail

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import kotlinx.coroutines.launch

@Composable
fun MailAccountScreen(account: ImapSmtpAccount, onBack: () -> Unit, onConnected: () -> Unit) {
    val config = remember { runCatching { account.config() }.getOrNull() }
    var email by remember { mutableStateOf(config?.email.orEmpty()) }
    var username by remember { mutableStateOf(config?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf(config?.imapHost.orEmpty()) }
    var imapPort by remember { mutableStateOf(config?.imapPort?.toString() ?: "993") }
    var smtp by remember { mutableStateOf(config?.smtpHost.orEmpty()) }
    var port by remember { mutableStateOf(config?.smtpPort?.toString() ?: "465") }
    var message by remember { mutableStateOf("IMAP over TLS (993). SMTP TLS (465) or STARTTLS (587). Use your provider's app password. OAuth-only accounts require provider registration.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SidekitScaffold("Mail account", actions = listOf(SideAction.back("back", "Back", onBack), SideAction("connect", if (busy) "Connecting…" else "Connect") {
        if (!busy) { busy = true; scope.launch {
            try {
                account.connect(MailConfig(email.trim(), username.trim().ifBlank { email.trim() }, password.ifEmpty { config?.password.orEmpty() }, imap.trim(), smtp.trim(), port.toIntOrNull() ?: 0, imapPort.toIntOrNull() ?: 0))
                password = ""; onConnected()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Connection failed. Check hosts, ports, username and app password; both servers must accept the account." }
            finally { busy = false }
        } }
    }), options = listOf(SideAction("disconnect", "Disconnect") { account.disconnect(); onBack() })) { padding ->
        SidekitList(padding) {
            item { SidekitCard(Modifier.fillMaxWidth()) { SidekitText(message, maxLines = Int.MAX_VALUE) } }
            item { SidekitTextField(email, { email = it }, Modifier.fillMaxWidth(), placeholder = "Email", singleLine = true) }
            item { SidekitTextField(username, { username = it }, Modifier.fillMaxWidth(), placeholder = "Username (or email)", singleLine = true) }
            item { hrln.interactive.sidekit.apps.integrations.SecretField(password, { password = it }, if (config == null) "App password" else "App password (blank keeps saved)") }
            item { SidekitTextField(imap, { imap = it }, Modifier.fillMaxWidth(), placeholder = "IMAP host", singleLine = true) }
            item { SidekitTextField(imapPort, { imapPort = it }, Modifier.fillMaxWidth(), placeholder = "IMAP TLS port (993)", singleLine = true, dictation = false) }
            item { SidekitTextField(smtp, { smtp = it }, Modifier.fillMaxWidth(), placeholder = "SMTP host", singleLine = true) }
            item { SidekitTextField(port, { port = it }, Modifier.fillMaxWidth(), placeholder = "SMTP port (465 or 587)", singleLine = true, dictation = false) }
        }
    }
}
