package hrln.interactive.sidekit.apps.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

data class DeviceCalendar(val id: Long, val name: String)
data class CalendarEvent(
    val id: Long, val title: String, val start: Long, val end: Long, val calendarId: Long,
    val allDay: Boolean = false, val recurring: Boolean = false,
) {
    val time: String get() = if (allDay) "All day" else DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()))
}
data class EventDraft(val id: Long? = null, val title: String = "", val start: Long, val end: Long,
    val calendarId: Long, val allDay: Boolean = false, val rrule: String? = null, val timezone: String = ZoneId.systemDefault().id)

interface CalendarRepository {
    suspend fun events(from: LocalDate, until: LocalDate): List<CalendarEvent>
    suspend fun calendars(): List<DeviceCalendar>
    suspend fun event(id: Long): EventDraft?
    suspend fun save(draft: EventDraft)
    suspend fun delete(id: Long)
}

class DeviceCalendarRepository(context: Context) : CalendarRepository {
    private val resolver = context.applicationContext.contentResolver
    override suspend fun events(from: LocalDate, until: LocalDate): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        // A UTC envelope also includes all-day events, whose provider timestamps are UTC dates.
        val begin = minOf(from.atStartOfDay(zone).toInstant().toEpochMilli(), from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val end = maxOf(until.atStartOfDay(zone).toInstant().toEpochMilli(), until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, begin); ContentUris.appendId(it, end) }.build()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.RRULE)
        val result = mutableListOf<CalendarEvent>()
        resolver.query(uri, columns, "${CalendarContract.Instances.VISIBLE}=1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val event = CalendarEvent(c.getLong(0), c.getString(1).orEmpty().ifBlank { "Untitled" }, c.getLong(2), c.getLong(3), c.getLong(4), c.getInt(5) != 0, !c.getString(6).isNullOrBlank())
                val z = if (event.allDay) ZoneOffset.UTC else zone
                val rangeStart = from.atStartOfDay(z).toInstant().toEpochMilli()
                val rangeEnd = until.atStartOfDay(z).toInstant().toEpochMilli()
                if (event.start < rangeEnd && event.end > rangeStart) result += event
            }
        } ?: error("Calendar provider unavailable")
        result
    }
    override suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DeviceCalendar>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
            while (c.moveToNext()) result += DeviceCalendar(c.getLong(0), c.getString(1).orEmpty())
        } ?: error("Calendar provider unavailable")
        result
    }
    override suspend fun event(id: Long): EventDraft? = withContext(Dispatchers.IO) {
        resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE,
                CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.DURATION), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val start = c.getLong(1)
            val duration = c.getString(7)?.let { runCatching { Duration.parse(it).toMillis() }.getOrNull() } ?: 3600000
            EventDraft(id, c.getString(0).orEmpty(), start, if (c.isNull(2)) start + duration else c.getLong(2), c.getLong(3), c.getInt(4) != 0, c.getString(5), c.getString(6) ?: ZoneId.systemDefault().id)
        }
    }
    override suspend fun save(draft: EventDraft) = withContext(Dispatchers.IO) {
        require(draft.title.isNotBlank() && draft.end > draft.start)
        require(calendars().any { it.id == draft.calendarId }) { "This calendar is read-only or no longer available." }
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, draft.title.trim())
            put(CalendarContract.Events.DTSTART, draft.start)
            if (draft.rrule.isNullOrBlank()) put(CalendarContract.Events.DTEND, draft.end)
            else {
                put(CalendarContract.Events.DURATION, if (draft.allDay) "P${(draft.end - draft.start) / 86400000}D" else "PT${(draft.end - draft.start) / 1000}S")
                putNull(CalendarContract.Events.DTEND)
            }
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.timezone)
        }
        if (draft.id == null) check(resolver.insert(CalendarContract.Events.CONTENT_URI, values) != null) { "Event was not saved." }
        else check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, draft.id), values, null, null) == 1) { "Event no longer exists." }
    }
    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        check(resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) == 1) { "Event no longer exists." }
    }
    fun changes() = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { trySend(Unit) } }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
}
