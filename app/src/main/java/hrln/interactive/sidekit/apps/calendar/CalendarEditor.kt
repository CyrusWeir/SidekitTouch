package hrln.interactive.sidekit.apps.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import hrln.interactive.sidekit.design.theme.SidekitTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

val EventDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

@Composable
fun CalendarEditor(id: Long?, date: LocalDate, onClose: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val repo = remember { DeviceCalendarRepository(context) }
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var original by remember { mutableStateOf<EventDraft?>(null) }
    var ready by remember { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf<String?>(null) }
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var end by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarId by rememberSaveable { mutableStateOf<Long?>(null) }
    var allDay by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var choosingCalendar by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            calendars = repo.calendars()
            original = id?.let { repo.event(it) }
            if (id != null && original == null) { error = "This event no longer exists."; return@LaunchedEffect }
            val event = original
            val zone = if (event?.allDay == true) ZoneOffset.UTC else event?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            if (title == null) {
                title = event?.title.orEmpty()
                allDay = event?.allDay ?: false
                start = event?.start?.let { EventDateFormat.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "${date} 09:00"
                end = event?.end?.let { EventDateFormat.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "${date} 10:00"
                calendarId = event?.calendarId ?: calendars.firstOrNull()?.id
            }
            ready = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not open event calendars. Check calendar access and try again." }
    }
    fun save() {
        if (!ready || busy) return
        val calendar = calendarId
        if (calendar == null || calendars.none { it.id == calendar }) { error = "Choose a writable device calendar."; return }
        val zone = if (allDay) ZoneOffset.UTC else original?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val begin = runCatching { LocalDateTime.parse(start, EventDateFormat).let { if (allDay) it.toLocalDate().atStartOfDay() else it }.atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        val finish = runCatching { LocalDateTime.parse(end, EventDateFormat).let { if (allDay) it.toLocalDate().atStartOfDay() else it }.atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        if (title.isNullOrBlank() || begin == null || finish == null || finish <= begin) {
            error = "Enter a title and YYYY-MM-DD HH:mm times. End must be after start."; return
        }
        busy = true
        scope.launch {
            try { repo.save(EventDraft(id, title.orEmpty(), begin, finish, calendar, allDay, original?.rrule, zone.id)); onClose() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not save. Check calendar access and try again." }
            finally { busy = false }
        }
    }
    BackHandler(choosingCalendar || deleting || busy) { if (!busy) { choosingCalendar = false; deleting = false } }
    SidekitScaffold(focusParking = focus, title = if (choosingCalendar) "Choose calendar" else if (id == null) "New event" else "Edit event",
        actions = when {
            busy -> listOf(SideAction("main", "Saving…") {})
            choosingCalendar -> listOf(SideAction("main", "Back") { choosingCalendar = false })
            deleting -> listOf(SideAction("cancel", "Cancel") { deleting = false }, SideAction("main", if (original?.rrule.isNullOrBlank()) "Delete" else "Delete series") {
                if (id != null) { busy = true; scope.launch {
                    try { repo.delete(id); onClose() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not delete. Check calendar access and try again."; deleting = false }
                    finally { busy = false }
                } }
            })
            else -> listOf(SideAction("cancel", "Cancel", onClick = onClose), SideAction("main", "Save", onClick = ::save))
        }, options = if (id == null || busy || choosingCalendar) emptyList() else listOf(SideAction("delete", "Delete") { deleting = true })) { padding ->
        val list = rememberLazyListState()
        SidekitList(padding, state = list) {
            if (choosingCalendar) calendars.forEachIndexed { index, calendar -> item { SidekitItem(calendar.name, Modifier.listFade(list, index), onClick = { calendarId = calendar.id; choosingCalendar = false }) } }
            else {
                error?.let { item { SidekitItem(it, Modifier.listFade(list, 0)) } }
                if (!ready && error == null) item { SidekitItem("Loading…", Modifier.listFade(list, 0)) }
                if (ready) {
                    if (!original?.rrule.isNullOrBlank()) item { SidekitItem("Changes and deletion apply to the whole recurring series.", Modifier.listFade(list, 0)) }
                    item { SidekitCard(Modifier.listFade(list, 1).fillMaxWidth()) { SidekitTextField(title.orEmpty(), { title = it }, placeholder = "Title", focusOnKeypad = true, autoFocus = id == null, singleLine = true, onDone = { focus.requestFocus() }) } }
                    item { SidekitCard(Modifier.listFade(list, 2).fillMaxWidth()) { Column {
                        SidekitText("Start · YYYY-MM-DD HH:mm", style = SidekitTheme.typography.caption)
                        SidekitTextField(start.orEmpty(), { start = it }, singleLine = true, onDone = { focus.requestFocus() })
                    } } }
                    item { SidekitCard(Modifier.listFade(list, 3).fillMaxWidth()) { Column {
                        SidekitText("End · YYYY-MM-DD HH:mm", style = SidekitTheme.typography.caption)
                        SidekitTextField(end.orEmpty(), { end = it }, singleLine = true, onDone = { focus.requestFocus() })
                    } } }
                    item { SidekitItem(if (allDay) "All day · End date is exclusive" else "Timed event", Modifier.listFade(list, 4), onClick = { allDay = !allDay }) }
                    item { SidekitItem(calendars.find { it.id == calendarId }?.name ?: "No writable calendar", Modifier.listFade(list, 5), onClick = { choosingCalendar = true }) }
                    if (calendars.isEmpty()) item { SidekitItem("Add a calendar account in Android settings first. Sidekit saves events to your device calendars.", Modifier.listFade(list, 6)) }
                }
            }
        }
    }
}
