package hrln.interactive.sidekit.apps.calculator

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*

data class Calculation(val expression: String, val result: String)
interface CalculationStore { fun read(): List<Calculation>; fun write(history: List<Calculation>) }
class FileCalculationStore(file: File) : CalculationStore {
    private val file = AtomicFile(file)
    override fun read(): List<Calculation> {
        val stream = try { file.openRead() } catch (_: FileNotFoundException) { return emptyList() }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            val size = input.readInt(); require(size in 0..50)
            List(size) { Calculation(input.readUTF(), input.readUTF()) }
        }
    }
    override fun write(history: List<Calculation>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(history.size)
            history.forEach { data.writeUTF(it.expression); data.writeUTF(it.result) }
            data.flush(); file.finishWrite(out)
        } catch (e: Exception) { file.failWrite(out); throw e }
    }
}
class CalculatorHistory(store: CalculationStore, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow<List<Calculation>>(emptyList())
    val entries = mutable.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private val writes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var cleared = false
    init {
        scope.launch(io) {
            val loaded = try { store.read() } catch (_: Exception) { error.value = "History could not be read."; emptyList() }
            synchronized(this@CalculatorHistory) { if (!cleared) mutable.value = (mutable.value + loaded).take(50) }
            for (signal in writes) try { store.write(mutable.value); error.value = null } catch (_: Exception) { error.value = "History could not be saved." }
        }
    }
    @Synchronized fun add(entry: Calculation) { mutable.value = (listOf(entry) + mutable.value).take(50); writes.trySend(Unit) }
    @Synchronized fun clear() { cleared = true; mutable.value = emptyList(); writes.trySend(Unit) }
    companion object {
        private var instance: CalculatorHistory? = null
        @Synchronized fun get(context: Context) = instance ?: CalculatorHistory(FileCalculationStore(File(context.filesDir, "calculator.bin")), CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
    }
}
