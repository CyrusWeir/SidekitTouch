package hrln.interactive.sidekit.apps.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeBodyPart
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
// GreenMail enables the legacy SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA cipher when it starts its
// TLS servers. Robolectric's Conscrypt provider does not support it, so the servers fail to start.
// Robolectric only disables Conscrypt by default on macOS aarch64, so this previously passed only
// on Apple-silicon Macs. Force it off here so the test behaves the same on every platform.
@ConscryptMode(ConscryptMode.Mode.OFF)
class ImapSmtpAccountTest {
    @Test fun `TLS account logs in reads MIME inbox and submits actual SMTP envelope`() = runTest {
        val server = GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imaps"),ServerSetup(0,"127.0.0.1","smtps")))
        server.start()
        try {
            val user = server.setUser("me@example.com", "me", "test-password")
            server.setUser("recipient@example.com", "recipient", "test-password")
            val seed = MimeMessage(Session.getInstance(Properties())).apply {
                setFrom("sender@example.com"); setRecipients(javax.mail.Message.RecipientType.TO,"me@example.com"); subject="Inbox subject"
                val alternatives = MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply { setText("Plain inbox text","UTF-8") })
                    addBodyPart(MimeBodyPart().apply { setContent("<b>HTML alternative</b>","text/html; charset=UTF-8") })
                }
                setContent(alternatives); saveChanges()
            }
            user.deliver(seed)
            var saved: MailConfig? = null
            val store = object : MailConfigStore {
                override fun read() = saved
                override fun write(config: MailConfig) { saved = config }
                override fun clear() { saved = null }
            }
            // The fixture certificate is self-signed with a non-localhost CN. Relax it only in this test session.
            val account = ImapSmtpAccount(store) { c -> ImapSmtpAccount.session(c).apply {
                properties["mail.imaps.ssl.trust"] = "localhost"
                properties["mail.smtps.ssl.trust"] = "localhost"
                properties["mail.imaps.ssl.checkserveridentity"] = "false"
                properties["mail.smtps.ssl.checkserveridentity"] = "false"
            } }
            val config = MailConfig("me@example.com","me","test-password","localhost","localhost",server.smtps.port,server.imaps.port)
            account.connect(config)
            assertTrue(account.configured)
            val inbox = account.inbox().single()
            assertEquals("Inbox subject",inbox.subject)
            assertEquals("Plain inbox text",inbox.body.trim())
            assertTrue(inbox.remoteKey.startsWith("me@example.com|"))
            assertEquals(MailSendResult.Sent, account.send(MailMessage("me", "SMTP test", "Actual wire body", "recipient@example.com")))
            assertTrue(server.waitForIncomingEmail(3000,2))
            assertEquals("Actual wire body", server.receivedMessages.first { it.subject == "SMTP test" }.content.toString().trim())
            account.disconnect(); assertFalse(account.configured)
        } finally { server.stop() }
    }
    @Test fun `certificate hostname mismatch fails account setup and never saves credentials`() = runTest {
        val server = GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imaps")))
        server.start()
        try {
            server.setUser("me@example.com","me","test-password")
            var saved = false
            val store = object : MailConfigStore {
                override fun read(): MailConfig? = null
                override fun write(config: MailConfig) { saved = true }
                override fun clear() {}
            }
            val account = ImapSmtpAccount(store) { c -> ImapSmtpAccount.session(c).apply { properties["mail.imaps.ssl.trust"] = "localhost" } }
            try { account.connect(MailConfig("me@example.com","me","test-password","localhost","localhost",465,server.imaps.port)); fail("Accepted certificate for wrong host") }
            catch (expected: javax.mail.MessagingException) { assertTrue(expected.message!!.contains("verify identity")) }
            assertFalse(saved)
        } finally { server.stop() }
    }
}
