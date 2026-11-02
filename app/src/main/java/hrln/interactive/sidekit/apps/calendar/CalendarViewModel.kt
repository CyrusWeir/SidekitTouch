package hrln.interactive.sidekit.apps.calendar

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CalendarDay(val weekday: String, val date: Int)

data class CalendarEvent(val time: String, val title: String)

data class CalendarUiState(
    val month: String = CalendarSampleData.month,
    val week: List<CalendarDay> = CalendarSampleData.week,
    val selectedIndex: Int = 0,
    private val eventsByDate: Map<Int, List<CalendarEvent>> = CalendarSampleData.eventsByDate,
) {
    val selectedDay: CalendarDay get() = week[selectedIndex]
    val events: List<CalendarEvent> get() = eventsByDate[selectedDay.date].orEmpty()
}

class CalendarViewModel : ViewModel() {
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    /** Selects a day of the shown week; out-of-range indices are ignored. */
    fun selectDay(index: Int) = _state.update { if (index in it.week.indices) it.copy(selectedIndex = index) else it }
}

object CalendarSampleData {
    // Placeholder content from "Calendar / Home" (96:420).
    const val month = "November 2026"
    val week = listOf(
        CalendarDay("M", 2), CalendarDay("T", 3), CalendarDay("W", 4), CalendarDay("T", 5),
        CalendarDay("F", 6), CalendarDay("S", 7), CalendarDay("S", 8),
    )
    val eventsByDate = mapOf(
        2 to listOf(CalendarEvent("9:30", "Team catch-up"), CalendarEvent("12:00", "Lunch with Alex")),
    )
}
