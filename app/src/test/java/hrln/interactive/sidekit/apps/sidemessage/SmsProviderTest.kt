package hrln.interactive.sidekit.apps.sidemessage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class SmsProviderTest {
    @Test fun paginationKeepsTimestampTiesAndMmsSecondsInOrder() {
        val sms = TestMessages(false)
        val mms = TestMessages(true)
        ShadowContentResolver.registerProviderInternal("sms", sms)
        ShadowContentResolver.registerProviderInternal("mms", mms)
        sms.db.execSQL("INSERT INTO messages VALUES (11, 7, 'newer', 1, 2001, -1, '0400')")
        sms.db.execSQL("INSERT INTO messages VALUES (10, 7, 'tie 10', 1, 2000, -1, '0400')")
        sms.db.execSQL("INSERT INTO messages VALUES (9, 7, 'tie 9', 1, 2000, -1, '0400')")
        mms.db.execSQL("INSERT INTO messages VALUES (1, 7, 2, 1, 132)")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = AndroidMessageSource(context.contentResolver)
        val first = source.messages(7, 2)
        assertEquals(listOf("s11", "s10"), first.map { it.key })
        val second = source.messages(7, 2, first.last())
        assertEquals(listOf("s9", "m1"), second.map { it.key })
        assertEquals(emptyList<Message>(), source.messages(7, 2, second.last()))
        assertEquals("0400", first.first().address)
        sms.db.close()
        mms.db.close()
    }

    @Test fun pendingMmsCanBeRetriedAndDownloadedPartsExposeViewableAttachments() {
        val sms = TestMessages(false); val mms = TestMessages(true)
        ShadowContentResolver.registerProviderInternal("sms", sms)
        ShadowContentResolver.registerProviderInternal("mms", mms)
        mms.db.execSQL("INSERT INTO messages VALUES (2, 7, 3, 1, 130)")
        mms.db.execSQL("INSERT INTO messages VALUES (1, 7, 2, 1, 132)")
        mms.db.execSQL("INSERT INTO part VALUES (1, 'text/plain', 'Hello', 10, 'text.txt')")
        mms.db.execSQL("INSERT INTO part VALUES (1, 'image/jpeg', null, 11, 'photo.jpg')")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = AndroidMessageSource(context.contentResolver).messages(7, 10)
        assertEquals("content://mms/2", messages.first().pendingMms)
        assertEquals("MMS pending · tap to download or retry",messages.first().text)
        assertEquals("[Picture] Hello",messages.last().text)
        assertEquals(listOf(MmsAttachment("content://mms/part/11","image/jpeg","photo.jpg")),messages.last().attachments)
        sms.db.close(); mms.db.close()
    }

    @Test fun deletingMmsUsesProviderCleanup() {
        val mms = mmsWithAttachment()
        try {
            val transport = AndroidSmsTransport(ApplicationProvider.getApplicationContext<Context>())
            assertTrue(transport.delete(Message("Hello", false, key = "m1", rowId = 1)))
            assertEquals(0, mms.count("messages"))
            assertEquals(0, mms.count("part"))
        } finally { mms.db.close() }
    }

    @Test fun refusedMmsDeletionPreservesContents() {
        for (throws in listOf(false, true)) {
            val mms = mmsWithAttachment()
            try {
                mms.refuseDelete = true
                mms.throwOnDelete = throws
                val transport = AndroidSmsTransport(ApplicationProvider.getApplicationContext<Context>())
                assertFalse(transport.delete(Message("Hello", false, key = "m1", rowId = 1)))
                assertEquals(1, mms.count("messages"))
                assertEquals(1, mms.count("part"))
            } finally { mms.db.close() }
        }
    }

    private fun mmsWithAttachment() = TestMessages(true).also { mms ->
        ShadowContentResolver.registerProviderInternal("mms", mms)
        mms.db.execSQL("INSERT INTO messages VALUES (1, 7, 2, 1, 132)")
        mms.db.execSQL("INSERT INTO part VALUES (1, 'image/jpeg', null, 11, 'photo.jpg')")
    }

    /** Executes the production selections; rejects SQL LIMIT like strict Telephony providers. */
    private class TestMessages(private val mms: Boolean) : ContentProvider() {
        val db = SQLiteDatabase.create(null).apply {
            execSQL(if (mms) "CREATE TABLE messages (_id INTEGER, thread_id INTEGER, date INTEGER, msg_box INTEGER, m_type INTEGER)"
                else "CREATE TABLE messages (_id INTEGER, thread_id INTEGER, body TEXT, type INTEGER, date INTEGER, status INTEGER, address TEXT)")
            execSQL("CREATE TABLE part (mid INTEGER, ct TEXT, text TEXT, _id INTEGER, cl TEXT)")
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (sortOrder?.contains("LIMIT") == true) throw IllegalArgumentException("LIMIT rejected")
            return db.query(if (mms && uri.path == "/part") "part" else "messages", projection,
                selection, selectionArgs, null, null, sortOrder)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        var refuseDelete = false
        var throwOnDelete = false
        fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri.path == "/part") return db.delete("part", selection, selectionArgs)
            if (throwOnDelete) throw SecurityException("SMS role revoked")
            if (refuseDelete) return 0
            val id = uri.lastPathSegment!!
            val removed = db.delete("messages", "_id=?", arrayOf(id))
            if (mms && removed > 0) db.delete("part", "mid=?", arrayOf(id))
            return removed
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
