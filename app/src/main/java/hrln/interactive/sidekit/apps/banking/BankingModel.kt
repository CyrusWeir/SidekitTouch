package hrln.interactive.sidekit.apps.banking

import androidx.compose.runtime.Immutable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** An account the user tracks. Money is in minor units (cents) to avoid floating point. */
@Immutable
data class Account(val id: Long, val name: String, val openingCents: Long, val createdAt: Long)

/**
 * A recorded movement of money. [cents] is negative for money out (a payment) and positive for
 * money in. [payee] is who was paid, or who paid.
 */
@Immutable
data class Transaction(
    val id: Long,
    val accountId: Long,
    val payee: String,
    val cents: Long,
    val note: String,
    val at: Long,
)

/** Everything Banking stores: one small encrypted file. */
@Immutable
data class BankBook(
    val accounts: List<Account> = emptyList(),
    /** Newest first. */
    val transactions: List<Transaction> = emptyList(),
    /** Account shown on the home screen. */
    val selectedAccountId: Long? = null,
) {
    fun balance(accountId: Long): Long {
        val account = accounts.firstOrNull { it.id == accountId } ?: return 0
        return account.openingCents + transactions.sumOf { if (it.accountId == accountId) it.cents else 0L }
    }

    val selected: Account? get() = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.firstOrNull()

    fun transactionsOf(accountId: Long): List<Transaction> = transactions.filter { it.accountId == accountId }

    val maxId: Long get() = maxOf(accounts.maxOfOrNull { it.id } ?: 0L, transactions.maxOfOrNull { it.id } ?: 0L)
}

/** Binary encoding of a [BankBook] (before encryption). Versioned so the format can grow. */
object BankCodec {
    private const val MAGIC = 0x534B424B // "SKBK"
    private const val VERSION = 1

    fun encode(book: BankBook): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(book.selectedAccountId ?: -1L)
            out.writeInt(book.accounts.size)
            for (a in book.accounts) {
                out.writeLong(a.id)
                out.writeUTF(a.name)
                out.writeLong(a.openingCents)
                out.writeLong(a.createdAt)
            }
            out.writeInt(book.transactions.size)
            for (t in book.transactions) {
                out.writeLong(t.id)
                out.writeLong(t.accountId)
                out.writeUTF(t.payee)
                out.writeLong(t.cents)
                out.writeUTF(t.note)
                out.writeLong(t.at)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): BankBook = DataInputStream(ByteArrayInputStream(data)).use { input ->
        if (input.readInt() != MAGIC) throw IOException("Not a Banking file")
        if (input.readInt() != VERSION) throw IOException("Unknown Banking file version")
        val selected = input.readLong().takeIf { it >= 0 }
        val accounts = List(input.readInt()) { Account(input.readLong(), input.readUTF(), input.readLong(), input.readLong()) }
        val transactions = List(input.readInt()) {
            Transaction(input.readLong(), input.readLong(), input.readUTF(), input.readLong(), input.readUTF(), input.readLong())
        }
        BankBook(accounts, transactions, selected)
    }
}

/** Amounts as the user types and reads them, in the device's currency. */
object Money {
    private val format: NumberFormat get() = NumberFormat.getCurrencyInstance(Locale.getDefault())

    /** "$2,480.50"; negative amounts get a true minus sign: "−$64.20". [signed] adds "+" to gains. */
    fun format(cents: Long, signed: Boolean = false): String {
        val text = format.format(BigDecimal.valueOf(kotlin.math.abs(cents), 2))
        return when {
            cents < 0 -> "−$text"
            signed && cents > 0 -> "+$text"
            else -> text
        }
    }

    /**
     * Parses what was typed into cents: "12", "12.5", "12,50", "$1,200.00". Returns null when it
     * isn't a positive amount. A lone comma followed by 1-2 digits is a decimal comma.
     */
    fun parse(text: String): Long? {
        var t = text.trim().filter { it.isDigit() || it == '.' || it == ',' }
        if (t.isEmpty()) return null
        val comma = t.lastIndexOf(',')
        if (comma >= 0 && '.' !in t && t.length - comma - 1 in 1..2) t = t.replace(',', '.') else t = t.replace(",", "")
        if (t.count { it == '.' } > 1) return null
        val value = t.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        return toCents(value)
    }

    /** Like [parse] but for a balance: zero and a leading minus (a credit card owing) are allowed. */
    fun parseBalance(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        val negative = t.startsWith("-") || t.startsWith("\u2212")
        val digits = if (negative) t.drop(1) else t
        if (digits.filter { it.isDigit() }.all { it == '0' } && digits.any { it.isDigit() }) return 0L
        val cents = parse(digits) ?: return null
        return if (negative) -cents else cents
    }

    /** Plain editable text for an amount: "2480.50", "-12.00". */
    fun plain(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    private fun toCents(value: BigDecimal): Long? = try {
        value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}
