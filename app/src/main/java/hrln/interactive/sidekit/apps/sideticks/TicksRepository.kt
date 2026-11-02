package hrln.interactive.sidekit.apps.sideticks

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*

data class Tick(val id: Int, val title: String, val ticked: Boolean = false, val dueAt: Long? = null, val notified: Boolean = false)

interface TickStore {
    fun read(): List<Tick>
    fun write(ticks: List<Tick>)
}

class FileTickStore(file: File) : TickStore {
    private val file = AtomicFile(file)
    override fun read(): List<Tick> {
        val stream = try { file.openRead() } catch (_: FileNotFoundException) { return emptyList() }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            check(input.readInt() == 1) { "Unknown reminder file version" }
            val count = input.readInt()
            require(count in 0..100000)
            List(count) { Tick(input.readInt(), input.readUTF(), input.readBoolean(), input.readLong().takeIf { it >= 0 }, input.readBoolean()) }
        }
    }
    override fun write(ticks: List<Tick>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(1); data.writeInt(ticks.size)
            ticks.forEach { data.writeInt(it.id); data.writeUTF(it.title); data.writeBoolean(it.ticked); data.writeLong(it.dueAt ?: -1); data.writeBoolean(it.notified) }
            data.flush(); file.finishWrite(out)
        } catch (e: Exception) { file.failWrite(out); throw e }
    }
}

fun interface TickScheduling { fun reconcile(before: List<Tick>, after: List<Tick>) }

class TicksRepository(
    private val store: TickStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val scheduling: TickScheduling = TickScheduling { _, _ -> },
) {
    private val mutable = MutableStateFlow<List<Tick>?>(null)
    val ticks = mutable.asStateFlow()
    private val persisted = MutableStateFlow<List<Tick>?>(null)
    val error = MutableStateFlow<String?>(null)
    init {
        scope.launch(io) {
            val loaded = try { store.read() } catch (_: Exception) {
                error.value = "Could not read reminders. Restart Sidekit to retry."; return@launch
            }
            mutable.value = loaded
            var previous = loaded
            persisted.value = loaded
            scheduling.reconcile(emptyList(), loaded)
            mutable.filterNotNull().conflate().collect { next ->
                if (next != persisted.value) {
                    try {
                        store.write(next)
                        scheduling.reconcile(previous, next)
                        previous = next
                        persisted.value = next
                        error.value = null
                    } catch (_: Exception) { error.value = "Could not save reminders. Make an edit to retry." }
                }
            }
        }
    }
    @Synchronized fun add(title: String, dueAt: Long?): Boolean {
        val list = mutable.value ?: return false
        if (title.isBlank()) return false
        val id = generateSequence { kotlin.random.Random.nextInt(1, Int.MAX_VALUE) }.first { candidate -> list.none { it.id == candidate } }
        mutable.value = listOf(Tick(id, title.trim().take(2000), dueAt = dueAt)) + list
        return true
    }
    fun toggle(id: Int) = mutate { list -> list.map { if (it.id == id) it.copy(ticked = !it.ticked) else it } }
    fun delete(ids: Set<Int>) = mutate { it.filterNot { tick -> tick.id in ids } }
    fun restore(ticks: List<Tick>, originalOrder: List<Int>) = mutate { current ->
        val ranks = originalOrder.withIndex().associate { it.value to it.index }
        (current + ticks.filter { t -> current.none { it.id == t.id } }).sortedBy { ranks[it.id] ?: -1 }
    }
    fun markNotified(id: Int) = mutate { it.map { t -> if (t.id == id) t.copy(notified = true) else t } }
    suspend fun awaitSaved() { persisted.first { it != null && it == mutable.value } }
    private fun mutate(change: (List<Tick>) -> List<Tick>) { mutable.update { it?.let(change) } }
}

object Sideticks {
    private var instance: TicksRepository? = null
    @Synchronized fun repository(context: Context): TicksRepository = instance ?: TicksRepository(
        FileTickStore(File(context.filesDir, "sideticks.bin")), CoroutineScope(SupervisorJob() + Dispatchers.IO),
        scheduling = ReminderAlarms(context.applicationContext),
    ).also { instance = it }
}
