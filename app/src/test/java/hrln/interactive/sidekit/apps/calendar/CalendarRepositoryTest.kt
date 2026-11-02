package hrln.interactive.sidekit.apps.calendar

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import java.time.*

class TestCalendarProvider : ContentProvider() {
    var values: ContentValues? = null
    var inserted = false
    var deleted: Uri? = null
    var range: Uri? = null
    override fun onCreate() = true
    override fun getType(uri: Uri) = "vnd.android.cursor.item/event"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection)
        when {
            uri.path!!.startsWith("/instances") -> {
                range = uri
                cursor.addRow(arrayOf<Any?>(7L, "Meeting", 1793577600000L, 1793581200000L, 1L, 0, null))
            }
            uri.path!!.startsWith("/calendars") -> cursor.addRow(arrayOf<Any?>(1L, "Personal"))
            uri.path!!.startsWith("/events") -> cursor.addRow(arrayOf<Any?>("Weekly", 1793577600000L, null, 1L, 0, "FREQ=WEEKLY", "UTC", "PT3600S"))
        }
        return cursor
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri { inserted = true; this.values = values; return ContentUris.withAppendedId(uri, 42) }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { this.values = values; return 1 }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = uri; return 1 }
}

@RunWith(RobolectricTestRunner::class)
class CalendarRepositoryTest {
    private lateinit var provider: TestCalendarProvider
    private lateinit var repo: DeviceCalendarRepository
    @Before fun setup() {
        provider = TestCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        repo = DeviceCalendarRepository(ApplicationProvider.getApplicationContext())
    }
    @Test fun `creates event in selected writable calendar with actual timestamps`() = runTest {
        repo.save(EventDraft(title = " Lunch ", start = 1000, end = 3601000, calendarId = 1))
        assertTrue(provider.inserted)
        assertEquals("Lunch", provider.values!!.getAsString(CalendarContract.Events.TITLE))
        assertEquals(1L, provider.values!!.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals(3601000L, provider.values!!.getAsLong(CalendarContract.Events.DTEND))
    }
    @Test fun `recurring edit reads duration and writes duration instead of conflicting end`() = runTest {
        val original = repo.event(7)!!
        assertEquals(3600000L, original.end - original.start)
        assertEquals("FREQ=WEEKLY", original.rrule)
        repo.save(original.copy(title = "Updated"))
        assertNull(provider.values!!.get(CalendarContract.Events.DTEND))
        assertEquals("PT3600S", provider.values!!.getAsString(CalendarContract.Events.DURATION))
        assertFalse(provider.values!!.containsKey(CalendarContract.Events.RRULE))
    }
    @Test fun `all day write uses UTC and exclusive end`() = runTest {
        repo.save(EventDraft(title = "Holiday", start = 0, end = 86400000, calendarId = 1, allDay = true))
        assertEquals("UTC", provider.values!!.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(1, provider.values!!.getAsInteger(CalendarContract.Events.ALL_DAY))
    }
    @Test fun `delete addresses the selected event`() = runTest {
        repo.delete(7)
        assertEquals(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 7), provider.deleted)
    }
    @Test fun `queries instances with a bounded date range`() = runTest {
        repo.events(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        val segments = provider.range!!.pathSegments
        assertEquals("instances", segments[0])
        assertTrue(segments.last().toLong() > segments[segments.size - 2].toLong())
    }
}
