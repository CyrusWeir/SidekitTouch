package hrln.interactive.sidekit.apps.vault

import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class VaultRepositoryTest {
    private class TestCipher : VaultCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var fail = false
        override fun encrypt(bytes: ByteArray): ByteArray {
            check(!fail) { "Authentication expired" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(bytes)
        }
        override fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes, 12, bytes.size - 12)
        }
    }
    @Test fun `encrypted round trip keeps plaintext out of disk and rejects tampering`() {
        val dir = Files.createTempDirectory("vault-test").toFile()
        try {
            val file = File(dir, "vault.enc")
            val cipher = TestCipher()
            val store = VaultRepository(file, cipher)
            val notes = listOf(PrivateNote("id", "Private secret 🌱" + "x".repeat(70000)))
            store.write(notes)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("Private secret"))
            assertEquals(notes, VaultRepository(file, cipher).read())
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.read() }
        } finally { dir.deleteRecursively() }
    }
    @Test fun `failed encryption preserves the previous vault`() {
        val dir = Files.createTempDirectory("vault-test").toFile()
        try {
            val cipher = TestCipher()
            val store = VaultRepository(File(dir, "vault.enc"), cipher)
            val original = listOf(PrivateNote("1", "Keep me"))
            store.write(original)
            cipher.fail = true
            assertThrows(IllegalStateException::class.java) { store.write(emptyList()) }
            assertEquals(original, store.read())
        } finally { dir.deleteRecursively() }
    }
}
