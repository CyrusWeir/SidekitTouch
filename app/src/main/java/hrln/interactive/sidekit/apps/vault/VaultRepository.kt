package hrln.interactive.sidekit.apps.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All content and metadata are encrypted together; no plaintext temp files or backups. */
data class PrivateNote(val id: String, val body: String) {
    val title get() = body.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Untitled"
}
interface VaultCipher {
    fun encrypt(bytes: ByteArray): ByteArray
    fun decrypt(bytes: ByteArray): ByteArray
}
class KeystoreVaultCipher : VaultCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(60, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
    }
    override fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Unsupported or damaged vault" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return cipher.doFinal(bytes, 13, bytes.size - 13)
    }
    companion object { private const val ALIAS = "sidekit.private-notes.v1" }
}
interface VaultStore {
    fun read(): List<PrivateNote>
    fun write(notes: List<PrivateNote>)
}
class VaultRepository(file: File, private val cipher: VaultCipher) : VaultStore {
    private val file = AtomicFile(file)
    override fun read(): List<PrivateNote> {
        val encrypted = try { file.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { return emptyList() }
        val plain = cipher.decrypt(encrypted)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                require(input.readInt() == 1) { "Unsupported vault version" }
                val count = input.readInt().also { require(it in 0..10000) }
                List(count) {
                    val id = input.readUTF()
                    val size = input.readInt().also { require(it in 0..MAX_NOTE_BYTES) }
                    val text = ByteArray(size).also(input::readFully)
                    try { PrivateNote(id, text.toString(Charsets.UTF_8)) } finally { text.fill(0) }
                }
            }
        } finally { plain.fill(0) }
    }
    override fun write(notes: List<PrivateNote>) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeInt(notes.size)
            notes.forEach { note ->
                val text = note.body.toByteArray(Charsets.UTF_8)
                require(text.size <= MAX_NOTE_BYTES) { "Note is too large (maximum 1 MB)" }
                try { out.writeUTF(note.id); out.writeInt(text.size); out.write(text) } finally { text.fill(0) }
            }
        }
        val plain = buffer.toByteArray()
        val encrypted = try { cipher.encrypt(plain) } finally { plain.fill(0); buffer.reset() }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    companion object {
        private const val MAX_NOTE_BYTES = 1024 * 1024
        fun create(context: Context) = VaultRepository(File(context.noBackupFilesDir, "vault.enc"), KeystoreVaultCipher())
    }
}
