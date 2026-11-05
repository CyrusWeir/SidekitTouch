package hrln.interactive.sidekit.apps.clock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ClockAlarm(
    val id: String = UUID.randomUUID().toString(), val hour: Int = 7, val minute: Int = 0,
    val days: Set<Int> = emptySet(), val enabled: Boolean = true, val nextAt: Long = 0,
) {
    val time: String get() = "%02d:%02d".format(hour, minute)
    val repeat: String get() = if (days.isEmpty()) "Once" else days.sorted().joinToString(" ") { DayOfWeek.of(it).name.take(3) }
}

data class ClockTimer(val endAt: Long? = null, val remaining: Long = 0, val elapsedEndAt: Long? = null) {
    fun remainingAt(now: Long, uptime: Long? = null): Long = when {
        elapsedEndAt != null && uptime != null -> (elapsedEndAt - uptime).coerceAtLeast(0)
        endAt != null -> (endAt - now).coerceAtLeast(0)
        else -> remaining
    }
}

data class Stopwatch(val startedAt: Long? = null, val elapsed: Long = 0, val laps: List<Long> = emptyList()) {
    fun elapsedAt(now: Long) = elapsed + (startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)
}

data class Ringing(val id: String, val label: String, val at: Long)

/** An alarm waiting out its undo window before it is really removed. */
data class PendingDelete(val alarm: ClockAlarm, val token: Long)

data class ClockData(val alarms: List<ClockAlarm> = emptyList(), val timer: ClockTimer = ClockTimer(),
    val stopwatch: Stopwatch = Stopwatch(), val boot: Int = 0, val ringing: Ringing? = null)

fun nextAlarm(hour: Int, minute: Int, days: Set<Int>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (0..7).asSequence().map { today.plusDays(it.toLong()).atTime(hour, minute).atZone(zone) }
        .first { it.toInstant().toEpochMilli() > now && (days.isEmpty() || it.dayOfWeek.value in days) }.toInstant().toEpochMilli()
}

interface ClockStore { fun read(): ClockData; fun write(data: ClockData) }
class FileClockStore(file: File) : ClockStore {
    private val file = AtomicFile(file)
    override fun read(): ClockData {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return ClockData() }
        val json = input.bufferedReader().use { JSONObject(it.readText()) }
        require(json.getInt("version") == 1)
        val alarms = json.getJSONArray("alarms")
        val sw = json.getJSONObject("stopwatch")
        val laps = sw.getJSONArray("laps")
        val ring = json.optJSONObject("ringing")
        return ClockData(
            alarms = List(alarms.length()) { i -> alarms.getJSONObject(i).let { a ->
                val days = a.getJSONArray("days")
                ClockAlarm(a.getString("id"), a.getInt("hour"), a.getInt("minute"),
                    List(days.length()) { days.getInt(it) }.toSet(), a.getBoolean("enabled"), a.getLong("next"))
            } },
            timer = ClockTimer(json.optLong("timerEnd", -1).takeIf { it >= 0 }, json.getLong("timerRemaining"), json.optLong("timerElapsedEnd", -1).takeIf { it >= 0 }),
            stopwatch = Stopwatch(sw.optLong("start", -1).takeIf { it >= 0 }, sw.getLong("elapsed"), List(laps.length()) { laps.getLong(it) }),
            boot = json.getInt("boot"),
            ringing = ring?.let { Ringing(it.getString("id"), it.getString("label"), it.getLong("at")) },
        )
    }
    override fun write(data: ClockData) {
        val json = JSONObject().put("version", 1).put("boot", data.boot)
            .put("timerEnd", data.timer.endAt ?: -1).put("timerRemaining", data.timer.remaining).put("timerElapsedEnd", data.timer.elapsedEndAt ?: -1)
            .put("stopwatch", JSONObject().put("start", data.stopwatch.startedAt ?: -1).put("elapsed", data.stopwatch.elapsed).put("laps", JSONArray(data.stopwatch.laps)))
            .put("alarms", JSONArray(data.alarms.map { JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute)
                .put("days", JSONArray(it.days.toList())).put("enabled", it.enabled).put("next", it.nextAt) }))
        data.ringing?.let { json.put("ringing", JSONObject().put("id", it.id).put("label", it.label).put("at", it.at)) }
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}

fun interface ClockScheduling { fun reconcile(before: ClockData, after: ClockData) }
class ClockRepository(
    private val store: ClockStore, private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scheduling: ClockScheduling = ClockScheduling { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val boot: Int = 0,
) {
    private val mutable = MutableStateFlow<ClockData?>(null)
    val data = mutable.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private val _pendingIds = MutableStateFlow<Set<String>>(emptySet())

    /** Alarm ids hidden from the list while their deletion can still be undone. */
    val pendingIds: StateFlow<Set<String>> = _pendingIds.asStateFlow()

    private val _deleted = MutableStateFlow<PendingDelete?>(null)

    /** The alarm awaiting its undo window before being really removed, or null. */
    val deleted: StateFlow<PendingDelete?> = _deleted.asStateFlow()

    private var undoJob: Job? = null
    private val deleteTokens = AtomicLong(0)
    private val persisted = MutableStateFlow<ClockData?>(null)
    init {
        scope.launch(io) {
            val loaded = try { store.read() } catch (_: Exception) { error.value = "Could not load Clock data."; return@launch }
            mutable.value = if (loaded.boot != boot) loaded.copy(boot = boot, stopwatch = loaded.stopwatch.copy(startedAt = null), timer = loaded.timer.copy(elapsedEndAt = loaded.timer.endAt?.let { elapsed() + (it - now()).coerceAtLeast(0) })) else loaded
            var previous = ClockData()
            mutable.filterNotNull().conflate().collect { next ->
                try {
                    store.write(next)
                    scheduling.reconcile(previous, next)
                    previous = next
                    persisted.value = next
                    error.value = null
                } catch (_: Exception) { error.value = "Clock could not save or schedule. Please retry." }
            }
        }
    }
    fun save(alarm: ClockAlarm) {
        require(alarm.hour in 0..23 && alarm.minute in 0..59 && alarm.days.all { it in 1..7 })
        val updated = alarm.copy(nextAt = nextAlarm(alarm.hour, alarm.minute, alarm.days, now()))
        mutate { it.copy(alarms = (it.alarms.filterNot { a -> a.id == alarm.id } + updated).sortedBy { a -> a.hour * 60 + a.minute }) }
    }
    fun toggle(id: String) { data.value?.alarms?.find { it.id == id }?.let { save(it.copy(enabled = !it.enabled)) } }
    fun delete(id: String) = mutate { it.copy(alarms = it.alarms.filterNot { a -> a.id == id }, ringing = it.ringing?.takeUnless { r -> r.id == id }) }

    /**
     * Removes the alarm [id] from the list at once, but waits [UNDO_WINDOW_MS] before really
     * removing it so [undoDelete] can put it back. A delete still on offer is committed first.
     */
    fun deleteWithUndo(id: String) {
        commitPending()
        val alarm = data.value?.alarms?.find { it.id == id } ?: return
        val token = deleteTokens.incrementAndGet()
        _pendingIds.update { it + id }
        _deleted.value = PendingDelete(alarm, token)
        undoJob = scope.launch(io) {
            delay(UNDO_WINDOW_MS)
            val pending = _deleted.value?.takeIf { it.token == token } ?: return@launch
            _deleted.value = null
            finishDelete(pending.alarm.id)
        }
    }

    /** Puts back the alarm waiting out its window, if any. */
    fun undoDelete() {
        val pending = _deleted.value ?: return
        undoJob?.cancel()
        _deleted.value = null
        _pendingIds.update { it - pending.alarm.id }
    }

    /** Commits the deletion still on offer when a new one starts. */
    private fun commitPending() {
        val pending = _deleted.value ?: return
        _deleted.value = null
        scope.launch(io) { finishDelete(pending.alarm.id) }
    }

    /** Really removes the alarm that waited out its window. */
    private fun finishDelete(id: String) {
        delete(id)
        _pendingIds.update { it - id }
    }
    fun startTimer(duration: Long) { require(duration in 1000..604800000); mutate { it.copy(timer = ClockTimer(endAt = now() + duration, elapsedEndAt = elapsed() + duration)) } }
    fun pauseTimer() = mutate { it.copy(timer = ClockTimer(remaining = it.timer.remainingAt(now(), elapsed()))) }
    fun cancelTimer() = mutate { it.copy(timer = ClockTimer(), ringing = it.ringing?.takeUnless { r -> r.id == TIMER_ID }) }
    fun toggleStopwatch() = mutate {
        val sw = it.stopwatch
        it.copy(stopwatch = if (sw.startedAt == null) sw.copy(startedAt = elapsed()) else sw.copy(elapsed = sw.elapsedAt(elapsed()), startedAt = null))
    }
    fun lap() = mutate { if (it.stopwatch.startedAt == null) it else it.copy(stopwatch = it.stopwatch.copy(laps = (listOf(it.stopwatch.elapsedAt(elapsed())) + it.stopwatch.laps).take(100))) }
    fun resetStopwatch() = mutate { it.copy(stopwatch = Stopwatch()) }
    @Synchronized fun fire(id: String, trigger: Long): Ringing? {
        val current = data.value ?: return null
        val label: String
        if (id == TIMER_ID) {
            if (current.timer.endAt != trigger || current.timer.remainingAt(now(), elapsed()) > 0) return null
            label = "Timer finished"
            mutate { it.copy(timer = ClockTimer()) }
        } else {
            if (trigger > now()) return null
            val alarm = current.alarms.find { it.id == id && it.enabled && it.nextAt == trigger } ?: return null
            label = "Alarm ${alarm.time}"
            mutate { it.copy(alarms = it.alarms.map { a -> if (a.id != id) a else a.copy(enabled = a.days.isNotEmpty(), nextAt = nextAlarm(a.hour, a.minute, a.days, now())) }) }
        }
        val ring = Ringing(id, label, now())
        mutate { it.copy(ringing = ring) }
        return ring
    }
    fun dismiss() = mutate { it.copy(ringing = null) }
    fun snooze() = mutate { current ->
        val ring = current.ringing ?: return@mutate current
        if (ring.id == TIMER_ID) current.copy(ringing = null, timer = ClockTimer(endAt = now() + 300000, elapsedEndAt = elapsed() + 300000))
        else current.copy(ringing = null, alarms = current.alarms.map { if (it.id == ring.id) it.copy(enabled = true, nextAt = now() + 300000) else it })
    }
    fun timeChanged() = mutate { it.copy(alarms = it.alarms.map { a -> if (!a.enabled) a else a.copy(nextAt = nextAlarm(a.hour, a.minute, a.days, now())) }) }
    suspend fun awaitSaved() { persisted.first { it != null && it == data.value } }
    private fun mutate(change: (ClockData) -> ClockData) { mutable.update { it?.let(change) } }
    companion object {
        const val TIMER_ID = "timer"

        /** How long a deleted alarm can be restored from the action bar. */
        const val UNDO_WINDOW_MS = 5_000L
    }
}

object Clock {
    private var instance: ClockRepository? = null
    @Synchronized fun repository(context: Context) = instance ?: ClockRepository(
        FileClockStore(File(context.filesDir, "clock.json")), CoroutineScope(SupervisorJob() + Dispatchers.IO),
        scheduling = ClockAlarms(context.applicationContext), boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
    ).also { instance = it }
}
