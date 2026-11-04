package hrln.interactive.sidekit.apps.integrations

import android.content.Context
import androidx.core.util.AtomicFile
import hrln.interactive.sidekit.apps.banking.KeystoreCipher
import org.json.JSONObject
import java.io.File

/** Credentials stay in app-private storage, encrypted with a non-exportable Android Keystore key. */
class PrivateSettings(context: Context, name: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.sealed"))
    private val cipher = KeystoreCipher("sidekit_$name")
    @Synchronized fun read(): JSONObject = if (!file.baseFile.exists()) JSONObject() else
        JSONObject(String(cipher.decrypt(file.readFully()), Charsets.UTF_8))
    @Synchronized fun write(value: JSONObject) {
        val bytes = cipher.encrypt(value.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun clear() = file.delete()
}
