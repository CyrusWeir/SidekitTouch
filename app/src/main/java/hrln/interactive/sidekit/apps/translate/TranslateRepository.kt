package hrln.interactive.sidekit.apps.translate

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class TranslateLanguage(val code: String, val label: String, val speechCode: String) {
    English("en", "English", "en"),
    Chinese("zh-CN", "Chinese", "zh-CN"),
    Spanish("es", "Spanish", "es"),
    French("fr", "French", "fr"),
    German("de", "German", "de"),
    Japanese("ja", "Japanese", "ja"),
}

data class Translation(
    val id: Long,
    val sourceText: String,
    val translatedText: String,
    val source: TranslateLanguage,
    val target: TranslateLanguage,
    val at: Long,
)

/** A replaceable translation service; no credentials or account are kept in Sidekit. */
interface Translator {
    suspend fun translate(text: String, source: TranslateLanguage, target: TranslateLanguage): String
}

/** Free, keyless MyMemory endpoint. It requires a network connection and limits request length. */
class MyMemoryTranslator : Translator {
    override suspend fun translate(text: String, source: TranslateLanguage, target: TranslateLanguage): String =
        runInterruptible(Dispatchers.IO) {
            val query = "q=" + URLEncoder.encode(text.take(MAX_CHARS), "UTF-8") +
                "&langpair=" + URLEncoder.encode("${source.code}|${target.code}", "UTF-8")
            val connection = (URL("$ENDPOINT?$query").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val status = json.optInt("responseStatus", 0)
                if (status !in 200..299) throw IOException(json.optString("responseDetails", "Translation unavailable"))
                val translated = json.getJSONObject("responseData").getString("translatedText").trim()
                if (translated.isEmpty() || translated.equals("NO QUERY SPECIFIED. EXAMPLE REQUEST: GET?Q=HELLO&LANGPAIR=EN|IT", true)) {
                    throw IOException("No translation returned")
                }
                decodeEntities(translated)
            } finally {
                connection.disconnect()
            }
        }

    private fun decodeEntities(text: String): String = text
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    companion object {
        const val MAX_CHARS = 500
        private const val ENDPOINT = "https://api.mymemory.translated.net/get"
    }
}

interface TranslationStore {
    fun read(): List<Translation>
    fun write(history: List<Translation>)
}

class FileTranslationStore(file: File) : TranslationStore {
    private val file = AtomicFile(file)

    override fun read(): List<Translation> {
        val stream = try { file.openRead() } catch (_: IOException) { return emptyList() }
        return DataInputStream(BufferedInputStream(stream)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
            List(input.readInt()) {
                Translation(
                    input.readLong(), input.readUTF(), input.readUTF(),
                    TranslateLanguage.valueOf(input.readUTF()), TranslateLanguage.valueOf(input.readUTF()), input.readLong(),
                )
            }
        }
    }

    override fun write(history: List<Translation>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(history.size)
            history.forEach {
                data.writeLong(it.id)
                data.writeUTF(it.sourceText.take(MyMemoryTranslator.MAX_CHARS))
                data.writeUTF(it.translatedText.take(2_000))
                data.writeUTF(it.source.name)
                data.writeUTF(it.target.name)
                data.writeLong(it.at)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B5452
        const val VERSION = 1
    }
}

class TranslateRepository(
    private val store: TranslationStore,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _history = MutableStateFlow<List<Translation>?>(null)
    val history: StateFlow<List<Translation>?> = _history.asStateFlow()
    private var lastId = 0L

    init {
        scope.launch(io) {
            val loaded = try { store.read() } catch (_: Exception) { emptyList() }
            lastId = loaded.maxOfOrNull { it.id } ?: 0
            _history.value = loaded
            var written = loaded
            _history.filterNotNull().conflate().collect {
                if (it !== written) {
                    try { store.write(it); written = it } catch (_: IOException) {}
                }
            }
        }
    }

    @Synchronized
    fun add(sourceText: String, translatedText: String, source: TranslateLanguage, target: TranslateLanguage): Translation {
        val entry = Translation(++lastId, sourceText, translatedText, source, target, clock())
        _history.value = listOf(entry) + _history.value.orEmpty()
        return entry
    }

    fun remove(id: Long) { _history.value = _history.value.orEmpty().filterNot { it.id == id } }
    fun restore(entry: Translation) { _history.value = listOf(entry) + _history.value.orEmpty().filterNot { it.id == entry.id } }
    fun clear() { _history.value = emptyList() }
}

object Translate {
    @Volatile private var repository: TranslateRepository? = null
    fun repository(context: Context): TranslateRepository = repository ?: synchronized(this) {
        repository ?: TranslateRepository(
            FileTranslationStore(File(context.applicationContext.filesDir, "translations.bin")),
            CoroutineScope(SupervisorJob()),
        ).also { repository = it }
    }
}
