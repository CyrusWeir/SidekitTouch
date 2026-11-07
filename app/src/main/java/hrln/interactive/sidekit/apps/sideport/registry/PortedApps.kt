package hrln.interactive.sidekit.apps.sideport.registry

import android.content.Context
import android.content.SharedPreferences
import hrln.interactive.sidekit.system.SidekitBuild
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How a ported app opens. */
enum class PortMode {
    /** Drawn by Sidekit from the app's accessibility tree. */
    Projected,

    /** Shown as the app draws itself, with a small button back to the projection (games, maps, cameras). */
    Original,
}

/** An Android app the user has turned into a Sidekit app. */
data class PortedApp(val packageName: String, val label: String, val mode: PortMode = PortMode.Projected, val addedAt: Long = 0L)

/**
 * The apps added to Sideport, persisted in shared preferences. One process-wide instance so the
 * launcher, the Sideport screens and the projection service see the same list.
 */
class PortedApps internal constructor(private val prefs: SharedPreferences) {
    private val _apps = MutableStateFlow(read())
    val apps: StateFlow<List<PortedApp>> = _apps.asStateFlow()

    fun get(packageName: String): PortedApp? = _apps.value.firstOrNull { it.packageName == packageName }

    fun contains(packageName: String) = get(packageName) != null

    /** Adds [packageName], unless it is already there or is a Sidekit build: Sidekit never ports itself. */
    fun add(packageName: String, label: String) {
        if (SidekitBuild.isSidekit(packageName) || contains(packageName)) return
        write(_apps.value + PortedApp(packageName, label, addedAt = System.currentTimeMillis()))
    }

    fun remove(packageName: String) = write(_apps.value.filterNot { it.packageName == packageName })

    fun setMode(packageName: String, mode: PortMode) =
        write(_apps.value.map { if (it.packageName == packageName) it.copy(mode = mode) else it })

    private fun write(list: List<PortedApp>) {
        // Sidekit builds are never Sideport apps, including entries saved by an earlier version.
        val sorted = list.filterNot { SidekitBuild.isSidekit(it.packageName) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        _apps.value = sorted
        val array = JSONArray()
        sorted.forEach {
            array.put(JSONObject().put("package", it.packageName).put("label", it.label).put("mode", it.mode.name).put("added", it.addedAt))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun read(): List<PortedApp> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PortedApp(
                packageName = o.getString("package"),
                label = o.getString("label"),
                mode = runCatching { PortMode.valueOf(o.optString("mode")) }.getOrDefault(PortMode.Projected),
                addedAt = o.optLong("added"),
            )
        }.filterNot { SidekitBuild.isSidekit(it.packageName) }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY = "apps"

        @Volatile private var instance: PortedApps? = null

        fun get(context: Context): PortedApps = instance ?: synchronized(this) {
            instance ?: PortedApps(context.applicationContext.getSharedPreferences("sideport", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
