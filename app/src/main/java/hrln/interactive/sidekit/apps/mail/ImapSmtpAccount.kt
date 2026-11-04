package hrln.interactive.sidekit.apps.mail

import hrln.interactive.sidekit.apps.integrations.PrivateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Properties
import javax.mail.Folder
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.Multipart
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import android.text.Html

/** Only implicit TLS IMAP (993) and SMTP TLS/required STARTTLS are supported. */
data class MailConfig(val email: String, val username: String, val password: String, val imapHost: String, val smtpHost: String, val smtpPort: Int = 465, val imapPort: Int = 993) {
    fun validate() {
        InternetAddress(email, true).validate()
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter your username and app password" }
        require(listOf(imapHost, smtpHost).all { it.matches(Regex("[A-Za-z0-9.-]+")) && ('.' in it || it == "localhost") }) { "Enter valid IMAP and SMTP hostnames" }
        require(smtpPort in 1..65535 && imapPort in 1..65535) { "Enter valid mail ports" }
    }
    fun json() = JSONObject().put("email", email).put("username", username).put("password", password).put("imapHost", imapHost).put("smtpHost", smtpHost).put("smtpPort", smtpPort).put("imapPort", imapPort)
    companion object {
        fun parse(j: JSONObject): MailConfig? = if (!j.has("email")) null else MailConfig(j.getString("email"), j.getString("username"), j.getString("password"), j.getString("imapHost"), j.getString("smtpHost"), j.optInt("smtpPort",465), j.optInt("imapPort",993))
    }
}

interface MailConfigStore {
    fun read(): MailConfig?
    fun write(config: MailConfig)
    fun clear()
}
class ImapSmtpAccount(private val settings: MailConfigStore, private val sessions: (MailConfig) -> Session = { session(it) }) : MailAccount {
    constructor(settings: PrivateSettings) : this(object : MailConfigStore {
        override fun read() = MailConfig.parse(settings.read())
        override fun write(config: MailConfig) = settings.write(config.json())
        override fun clear() = settings.clear()
    })
    override val configured get() = config() != null
    fun config() = settings.read()
    suspend fun connect(config: MailConfig) = withContext(Dispatchers.IO) {
        config.validate()
        sessions(config).getStore("imaps").use { it.connect(config.imapHost, config.imapPort, config.username, config.password) }
        val protocol = if (config.smtpPort != 587) "smtps" else "smtp"
        sessions(config).getTransport(protocol).use { it.connect(config.smtpHost, config.smtpPort, config.username, config.password) }
        settings.write(config)
    }
    fun disconnect() = settings.clear()

    override suspend fun inbox(): List<MailMessage> = withContext(Dispatchers.IO) {
        val c = config() ?: throw IllegalStateException("Connect a mail account first")
        sessions(c).getStore("imaps").use { store ->
            store.connect(c.imapHost, c.imapPort, c.username, c.password)
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val uids = folder as UIDFolder
                val count = folder.messageCount
                if (count == 0) emptyList() else folder.getMessages(maxOf(1, count - 99), count).reversed().map { m ->
                    // A UID is only unique within this mailbox's UIDVALIDITY epoch.
                    val remoteKey = "${c.email}|${uids.uidValidity}|${uids.getUID(m)}"
                    MailMessage(m.from?.joinToString { a -> (a as? InternetAddress)?.toUnicodeString() ?: a.toString() }.orEmpty(),
                        m.subject.orEmpty(), text(m).take(50_000), c.email, folder = MailFolder.Inbox,
                        updatedAt = m.receivedDate?.time ?: m.sentDate?.time ?: 0, remoteKey = remoteKey)
                }
            } finally { folder.close(false) }
        }
    }
    override suspend fun send(message: MailMessage): MailSendResult = withContext(Dispatchers.IO) {
        val c = config() ?: throw IllegalStateException("Connect a mail account first")
        val addresses = InternetAddress.parse(message.to, true)
        require(addresses.isNotEmpty()) { "Add a recipient" }
        addresses.forEach { it.validate() }
        val session = sessions(c)
        val mime = MimeMessage(session).apply {
            setFrom(InternetAddress(c.email)); setRecipients(javax.mail.Message.RecipientType.TO, addresses)
            setSubject(message.subject, "UTF-8"); setText(message.body, "UTF-8"); sentDate = java.util.Date(); saveChanges()
        }
        val protocol = if (c.smtpPort != 587) "smtps" else "smtp"
        session.getTransport(protocol).use { transport ->
            transport.connect(c.smtpHost, c.smtpPort, c.username, c.password)
            transport.sendMessage(mime, mime.allRecipients)
        }
        MailSendResult.Sent
    }
    companion object {
        fun session(config: MailConfig): Session = Session.getInstance(Properties().apply {
            for (p in listOf("imaps", "smtp", "smtps")) {
                setProperty("mail.$p.connectiontimeout", "15000"); setProperty("mail.$p.timeout", "20000"); setProperty("mail.$p.writetimeout", "20000")
                setProperty("mail.$p.ssl.checkserveridentity", "true")
            }
            setProperty("mail.smtp.starttls.enable", "true"); setProperty("mail.smtp.starttls.required", "true")
            setProperty("mail.smtp.auth", "true"); setProperty("mail.smtps.auth", "true")
            setProperty("mail.imaps.partialfetch", "true"); setProperty("mail.imaps.fetchsize", "16384")
        })
        fun text(part: Part, depth: Int = 0): String {
            if (depth > 12 || part.disposition.equals(Part.ATTACHMENT, true)) return ""
            if (part.isMimeType("text/plain")) return part.inputStream.use { hrln.interactive.sidekit.apps.integrations.readLimited(it, 1024 * 1024).toString(charset(part)) }.take(50_000)
            if (part.isMimeType("text/html")) return Html.fromHtml(part.inputStream.use { hrln.interactive.sidekit.apps.integrations.readLimited(it, 1024 * 1024).toString(charset(part)) }, Html.FROM_HTML_MODE_LEGACY).toString().take(50_000)
            if (part.isMimeType("multipart/*")) {
                val multi = part.content as Multipart
                val parts = (0 until minOf(multi.count, 100)).map { multi.getBodyPart(it) }
                if (part.isMimeType("multipart/alternative")) return text(parts.firstOrNull { it.isMimeType("text/plain") } ?: parts.lastOrNull() ?: return "", depth + 1)
                return parts.joinToString("\n") { text(it, depth + 1) }.take(50_000)
            }
            return ""
        }
        private fun charset(part: Part): java.nio.charset.Charset = runCatching {
            java.nio.charset.Charset.forName(javax.mail.internet.ContentType(part.contentType).getParameter("charset") ?: "UTF-8")
        }.getOrDefault(Charsets.UTF_8)
    }
}
