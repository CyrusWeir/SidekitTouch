package hrln.interactive.sidekit.apps.mail

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.apps.everyday.InfoStrip
import hrln.interactive.sidekit.apps.everyday.TopAlignedRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class MailMessage(val sender: String, val subject: String)

object MailSampleData {
    // Placeholder content from "Mail / Home" (96:499).
    const val summary = "Inbox · 2 unread"
    val inbox = listOf(
        MailMessage("Alex", "Coffee tomorrow?"),
        MailMessage("Studio", "Design feedback"),
        MailMessage("Railway", "Your e-ticket"),
    )
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.mailGraph(nav: NavHostController) {
    composable(SubApp.Mail.route) { MailScreen(MailSampleData.summary, MailSampleData.inbox) }
}

/** "Mail / Home" (96:499). No message or "Folders"/"Compose" screen is mocked. */
@Composable
fun MailScreen(
    summary: String,
    messages: List<MailMessage>,
    onFolders: () -> Unit = {},
    onCompose: () -> Unit = {},
) {
    SidekitScaffold(
        title = "Mail",
        actions = dualArrowActions("Folders", "Compose", onFolders, onCompose),
    ) { padding ->
        EverydayContent(padding) {
            InfoStrip(summary, height = 62.dp, top = 13.dp, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
            messages.forEach {
                TopAlignedRow(it.sender, it.subject, height = SidekitTheme.spacing.itemHeight, top = 11.dp, titleStyle = SidekitTheme.typography.bodySmall)
            }
        }
    }
}

@SidephonePreview
@Composable
private fun MailPreview() = SidekitPreviewFrame { MailScreen(MailSampleData.summary, MailSampleData.inbox) }
