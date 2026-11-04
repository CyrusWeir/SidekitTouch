package hrln.interactive.sidekit.apps.banking

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connector metadata for the editable manual book. Up bank snapshots use [UpBankConnector]
 * separately so imported balances cannot be changed by local ledger entries. */
interface BankConnector {
    val displayName: String

    /** True only for a connector that talks to a real bank. */
    val isLive: Boolean

    /** The bank's view of accounts and transactions, or null when this connector has no feed. */
    suspend fun sync(): BankBook?
}

object ManualTracker : BankConnector {
    override val displayName = "Manual tracker"
    override val isLive = false
    override suspend fun sync(): BankBook? = null
}

/** Encrypts the Banking file. */
interface BookCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key generated inside the Android Keystore: the key never leaves secure
 * hardware (or the keystore daemon), so a copied file is unreadable off this phone. Output is
 * the 12-byte IV followed by ciphertext and tag.
 *
 * The key isn't bound to user authentication: Banking asks for the screen lock before it reads
 * the file instead (see [BankingGate]); binding the key would make every background save fail
 * once the auth window lapsed.
 */
class KeystoreCipher(private val alias: String = "sidekit_banking") : BookCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw GeneralSecurityException("Truncated")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Where the book lives. Called off the main thread only. */
interface BankStore {
    /** The stored book, an empty one if nothing was stored yet. Throws if it exists but can't be read. */
    fun read(): BankBook
    fun write(book: BankBook)
}

/** The book encrypted in one file, replaced atomically so a crash mid-write keeps the old copy. */
class EncryptedFileBankStore(file: File, private val cipher: BookCipher) : BankStore {
    private val file = AtomicFile(file)

    override fun read(): BankBook {
        val sealed = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return BankBook()
        }
        return try {
            BankCodec.decode(cipher.decrypt(sealed))
        } catch (e: GeneralSecurityException) {
            throw IOException("Banking data can't be decrypted", e)
        }
    }

    override fun write(book: BankBook) {
        val sealed = try {
            cipher.encrypt(BankCodec.encode(book))
        } catch (e: GeneralSecurityException) {
            throw IOException("Banking data can't be encrypted", e)
        }
        val out = file.startWrite()
        try {
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }
}

/**
 * The decrypted book, held in memory only while Banking is unlocked.
 *
 * Edits are synchronous and in-memory; saves run on [io] behind them, conflated so a burst of
 * edits is one encrypted write. [lock] drops the decrypted copy (pending saves still complete).
 */
class BankingRepository(
    private val store: BankStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    val connector: BankConnector = ManualTracker,
) {
    sealed interface Load {
        data object Locked : Load
        data object Loading : Load
        data class Open(val book: BankBook) : Load

        /** The file exists but couldn't be read. Nothing is written over it. */
        data object Unreadable : Load
    }

    private val _state = MutableStateFlow<Load>(Load.Locked)
    val state: StateFlow<Load> = _state.asStateFlow()

    private val pending = AtomicReference<BankBook?>(null)
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private var lastId = 0L

    init {
        scope.launch(io) {
            for (signal in saves) {
                val book = pending.getAndSet(null) ?: continue
                try {
                    store.write(book)
                } catch (_: IOException) {
                    // Keep it for the next edit to retry, unless something newer is queued.
                    pending.compareAndSet(null, book)
                }
            }
        }
    }

    val book: BankBook? get() = (_state.value as? Load.Open)?.book

    /** Reads and decrypts the book. Call only after the user has passed [BankingGate]. */
    suspend fun unlock() {
        if (_state.value is Load.Open) return
        _state.value = Load.Loading
        // A save queued just before the last lock is newer than the file.
        val queued = pending.get()
        val loaded = queued ?: withContext(io) {
            try {
                store.read()
            } catch (_: IOException) {
                null
            }
        }
        if (loaded == null) {
            _state.value = Load.Unreadable
            return
        }
        synchronized(this) { lastId = maxOf(lastId, loaded.maxId) }
        _state.value = Load.Open(loaded)
    }

    fun lock() {
        if (_state.value is Load.Open || _state.value is Load.Loading) _state.value = Load.Locked
    }

    /** After [Load.Unreadable], and only when the user confirms: replaces the old file with an empty book. */
    fun eraseUnreadable() {
        synchronized(this) {
            if (_state.value != Load.Unreadable) return
            val empty = BankBook()
            _state.value = Load.Open(empty)
            pending.set(empty)
        }
        saves.trySend(Unit)
    }

    fun addAccount(name: String, openingCents: Long): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || book == null) return null
        val account = Account(nextId(), trimmed.take(NAME_MAX), openingCents, clock())
        mutate { it.copy(accounts = it.accounts + account, selectedAccountId = it.selectedAccountId ?: account.id) }
        return account.id
    }

    fun renameAccount(id: Long, name: String, openingCents: Long) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutate { b -> b.copy(accounts = b.accounts.map { if (it.id == id) it.copy(name = trimmed.take(NAME_MAX), openingCents = openingCents) else it }) }
    }

    fun selectAccount(id: Long) = mutate { b -> if (b.accounts.any { it.id == id }) b.copy(selectedAccountId = id) else b }

    /** Deletes an account and its transactions; returns what was removed, for undo. */
    fun deleteAccount(id: Long): BankBook? {
        val current = book ?: return null
        val account = current.accounts.firstOrNull { it.id == id } ?: return null
        val removed = BankBook(listOf(account), current.transactionsOf(id))
        mutate { b ->
            val accounts = b.accounts.filter { it.id != id }
            b.copy(
                accounts = accounts,
                transactions = b.transactions.filter { it.accountId != id },
                selectedAccountId = if (b.selectedAccountId == id) accounts.firstOrNull()?.id else b.selectedAccountId,
            )
        }
        return removed
    }

    /** Records money out of (payment) or into (income) an account. Returns its id, or null if invalid. */
    fun record(accountId: Long, payee: String, cents: Long, note: String = "", incoming: Boolean = false): Long? {
        if (cents <= 0) return null
        val current = book ?: return null
        if (current.accounts.none { it.id == accountId }) return null
        val t = Transaction(nextId(), accountId, payee.trim().ifEmpty { if (incoming) "Income" else "Payment" }.take(NAME_MAX), if (incoming) cents else -cents, note.trim(), clock())
        mutate { it.copy(transactions = listOf(t) + it.transactions, selectedAccountId = accountId) }
        return t.id
    }

    fun deleteTransaction(id: Long): Transaction? {
        val t = book?.transactions?.firstOrNull { it.id == id } ?: return null
        mutate { b -> b.copy(transactions = b.transactions.filter { it.id != id }) }
        return t
    }

    /** Puts back what [deleteAccount] or [deleteTransaction] removed. */
    fun restore(removed: BankBook) = mutate { b ->
        val accounts = b.accounts + removed.accounts.filter { a -> b.accounts.none { it.id == a.id } }
        val transactions = (b.transactions + removed.transactions.filter { t -> b.transactions.none { it.id == t.id } })
            .sortedWith(NEWEST_FIRST)
        b.copy(accounts = accounts.sortedBy { it.createdAt }, transactions = transactions, selectedAccountId = b.selectedAccountId ?: accounts.firstOrNull()?.id)
    }

    private inline fun mutate(change: (BankBook) -> BankBook) {
        val next: BankBook
        synchronized(this) {
            val current = book ?: return
            next = change(current)
            if (next == current) return
            _state.value = Load.Open(next)
            pending.set(next)
        }
        saves.trySend(Unit)
    }

    @Synchronized
    private fun nextId(): Long {
        lastId = maxOf(lastId, book?.maxId ?: 0L) + 1
        return lastId
    }

    companion object {
        const val NAME_MAX = 60
        private val NEWEST_FIRST = compareByDescending<Transaction> { it.at }.thenByDescending { it.id }
    }
}

/** Process-wide [BankingRepository]. The decrypted book itself is dropped on lock. */
object Banking {
    @Volatile private var repository: BankingRepository? = null

    fun repository(context: Context): BankingRepository = repository ?: synchronized(this) {
        repository ?: BankingRepository(
            EncryptedFileBankStore(File(context.applicationContext.filesDir, "banking.sealed"), KeystoreCipher()),
            CoroutineScope(SupervisorJob()),
        ).also { repository = it }
    }
}
