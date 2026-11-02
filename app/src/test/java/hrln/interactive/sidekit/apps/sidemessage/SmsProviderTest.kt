package hrln.interactive.sidekit.apps.sidemessage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
        mms.db.execSQL("INSERT INTO messages VALUES (1, 7, 2, 1)")
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

    /** Executes the production selections; rejects SQL LIMIT like strict Telephony providers. */
    private class TestMessages(private val mms: Boolean) : ContentProvider() {
        val db = SQLiteDatabase.create(null).apply {
            execSQL(if (mms) "CREATE TABLE messages (_id INTEGER, thread_id INTEGER, date INTEGER, msg_box INTEGER)"
                else "CREATE TABLE messages (_id INTEGER, thread_id INTEGER, body TEXT, type INTEGER, date INTEGER, status INTEGER, address TEXT)")
            execSQL("CREATE TABLE part (mid INTEGER, ct TEXT, text TEXT)")
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (sortOrder?.contains("LIMIT") == true) throw IllegalArgumentException("LIMIT rejected")
            return db.query(if (mms && uri.path == "/part") "part" else "messages", projection,
                selection, selectionArgs, null, null, sortOrder)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
