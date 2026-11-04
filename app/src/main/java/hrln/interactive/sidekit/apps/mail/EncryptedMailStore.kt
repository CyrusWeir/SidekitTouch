package hrln.interactive.sidekit.apps.mail

import android.content.Context
import hrln.interactive.sidekit.apps.integrations.PrivateSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EncryptedMailStore(context: Context) : MailStore {
    private val settings = PrivateSettings(context, "mail_messages")
    private val legacy = File(context.filesDir, "mail.bin")
    override fun read(): List<MailMessage> {
        val json = settings.read()
        if (!json.has("messages") && legacy.exists()) {
            val messages = FileMailStore(legacy).read()
            write(messages)
            legacy.delete()
            return messages
        }
        val list = json.optJSONArray("messages") ?: return emptyList()
        return List(list.length()) { i -> val m = list.getJSONObject(i)
            MailMessage(m.getString("sender"),m.getString("subject"),m.getString("body"),m.getString("to"),m.getLong("id"),MailFolder.valueOf(m.getString("folder")),m.getLong("updatedAt"),m.optString("remoteKey"))
        }
    }
    override fun write(messages: List<MailMessage>) = settings.write(JSONObject().put("messages", JSONArray(messages.map { m ->
        JSONObject().put("sender",m.sender).put("subject",m.subject).put("body",m.body).put("to",m.to).put("id",m.id).put("folder",m.folder.name).put("updatedAt",m.updatedAt).put("remoteKey",m.remoteKey)
    })))
}
