package hrln.interactive.sidekit.apps.mail

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.apps.everyday.InfoStrip
import hrln.interactive.sidekit.apps.everyday.TopAlignedRow
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class MailMessage(
    val sender: String,
    val subject: String,
    val body: String = "",
    val to: String = "",
    val id: Long = 0,
    val folder: MailFolder = MailFolder.Inbox,
    val updatedAt: Long = 0,
    val remoteKey: String = "",
)

object MailSampleData {
    const val summary = "Inbox · 2 unread"
    val inbox = listOf(MailMessage("Alex", "Coffee tomorrow?"), MailMessage("Studio", "Design feedback"), MailMessage("Railway", "Your e-ticket"))
}

/** The original Figma frame, retained only for previews and screenshot tests. */
@Composable
fun MailScreen(summary: String, messages: List<MailMessage>, onFolders: () -> Unit = {}, onCompose: () -> Unit = {}) {
    SidekitScaffold(title = "Mail", actions = dualArrowActions("Folders", "Compose", onFolders, onCompose)) { padding ->
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
