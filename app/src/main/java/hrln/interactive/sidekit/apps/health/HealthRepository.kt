package hrln.interactive.sidekit.apps.health

import android.content.Context
import hrln.interactive.sidekit.apps.maps.OutdoorStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class StepDay(val date: String, val steps: Long, val approximate: Boolean)
/**
 * Only observed deltas are knowable without a permanent sensor registration. After reboot,
 * retain the previous subtotal and add the new boot's count; unobserved pre-reboot steps are
 * lost. On a missed midnight, assign the unsplittable delta to the newly observed day and
 * mark it approximate. Some devices stop counting entirely when no listener is registered.
 * The inexact alarm is rescheduled on each visit and sample; reboot recovery occurs on open.
 */
data class StepLedger(val day: String = "", val lastCounter: Long = -1, val boot: Int = -1, val today: Long = 0,
                      val approximate: Boolean = true, val goal: Int = 8000, val history: List<StepDay> = emptyList()) {
    fun sample(date: String, counter: Long, bootId: Int): StepLedger {
        require(counter >= 0)
        val reboot = boot != bootId || counter < lastCounter
        val delta = when { lastCounter < 0 -> 0; reboot -> counter; else -> counter - lastCounter }
        val changed = day.isNotEmpty() && date != day
        return copy(day = date, lastCounter = counter, boot = bootId,
            today = if (changed) delta else today + delta,
            approximate = if (changed) true else approximate || reboot,
            history = if (changed) (history.filterNot { it.date == day } + StepDay(day, today, approximate)).takeLast(365) else history)
    }
}

class HealthRepository(file: File, scope: CoroutineScope) {
    val store = OutdoorStore(file, ::decode, ::encode, StepLedger(), scope)
    fun sample(date: String, counter: Long, boot: Int) = store.edit { it.sample(date, counter, boot) }
    fun goal(value: Int) { require(value in 1..100_000); store.edit { it.copy(goal = value) } }
    companion object {
        fun encode(s: StepLedger) = JSONObject().put("day", s.day).put("counter", s.lastCounter).put("boot", s.boot).put("today", s.today).put("approximate", s.approximate).put("goal", s.goal).put("history", JSONArray(s.history.map { JSONObject().put("day", it.date).put("steps", it.steps).put("approximate", it.approximate) }))
        fun decode(j: JSONObject): StepLedger {
            val h = j.getJSONArray("history")
            return StepLedger(j.getString("day"), j.getLong("counter"), j.getInt("boot"), j.getLong("today"), j.getBoolean("approximate"), j.getInt("goal"), List(h.length()) { h.getJSONObject(it).let { d -> StepDay(d.getString("day"), d.getLong("steps"), d.getBoolean("approximate")) } })
        }
        private var instance: HealthRepository? = null
        @Synchronized fun get(context: Context): HealthRepository = instance ?: HealthRepository(File(context.filesDir, "health.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
    }
}
