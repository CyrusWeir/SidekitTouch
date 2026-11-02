package hrln.interactive.sidekit.apps.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class CalendarDay(val weekday: String, val date: Int)
enum class CalendarMode { Day, Month, Agenda }
data class CalendarUiState(
    val date: LocalDate = LocalDate.now(), val mode: CalendarMode = CalendarMode.Day,
    val events: List<CalendarEvent> = emptyList(), val loading: Boolean = false, val error: String? = null,
) {
    val month: String get() = date.format(DateTimeFormatter.ofPattern("MMMM uuuu"))
    val weekStart: LocalDate get() = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val week: List<CalendarDay> get() = (0..6).map { weekStart.plusDays(it.toLong()).let { d -> CalendarDay(d.dayOfWeek.name.take(1), d.dayOfMonth) } }
    val selectedIndex: Int get() = date.dayOfWeek.value - 1
}

class CalendarViewModel(private val repository: CalendarRepository, today: LocalDate = LocalDate.now()) : ViewModel() {
    private val mutable = MutableStateFlow(CalendarUiState(date = today))
    val state = mutable.asStateFlow()
    private var load: Job? = null
    fun selectDay(index: Int) { if (index in 0..6) selectDate(mutable.value.weekStart.plusDays(index.toLong())) }
    fun selectDate(date: LocalDate) { mutable.update { it.copy(date = date, mode = CalendarMode.Day) }; refresh() }
    fun mode(mode: CalendarMode) { mutable.update { it.copy(mode = mode) }; refresh() }
    fun move(delta: Long) { mutable.update { it.copy(date = if (it.mode == CalendarMode.Month) it.date.plusMonths(delta) else it.date.plusDays(delta)) }; refresh() }
    fun refresh() {
        load?.cancel()
        load = viewModelScope.launch {
            val current = mutable.value
            mutable.update { it.copy(loading = true, error = null) }
            val start = if (current.mode == CalendarMode.Month) current.date.withDayOfMonth(1) else current.date
            val end = when (current.mode) { CalendarMode.Month -> start.plusMonths(1); CalendarMode.Agenda -> start.plusDays(7); CalendarMode.Day -> start.plusDays(1) }
            try { val events = repository.events(start, end); mutable.update { it.copy(events = events, loading = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { mutable.update { it.copy(events = emptyList(), loading = false, error = "Calendar access was denied. Allow access to continue.") } }
            catch (_: Exception) { mutable.update { it.copy(events = emptyList(), loading = false, error = "Could not read calendars. Try again.") } }
        }
    }
    fun stop() { load?.cancel() }
}
