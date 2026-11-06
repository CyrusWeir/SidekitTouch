package hrln.interactive.sidekit.home

import android.content.Context
import android.content.SharedPreferences
import hrln.interactive.sidekit.apps.settings.visibilityId
import hrln.interactive.sidekit.navigation.SubApp
import org.json.JSONArray

/**
 * The launcher's home order, stored as stable app IDs.
 *
 * A single ordered home list drives everything: both the grid and the list render from the same
 * home order, and the dock is derived as the **first [DOCK_MAX] apps** of that order (no separate
 * pinned set — reordering an app into/out of the top four changes the dock). IDs are the existing
 * [visibilityId] strings (`builtin:Name` for Sidekit apps, `ported:package` for Sideport apps).
 *
 * Unknown or uninstalled IDs are kept in storage but skipped at render time, so a reinstalled app
 * returns to its slot. Newly-seen apps that are not yet in the stored order are appended at the end.
 *
 * Follows the [hrln.interactive.sidekit.design.input.KeyMapper] pattern: a default value, JSON
 * array encode/decode that preserves order, validation on read, atomic writes, and a
 * SharedPreferences observer for live reload.
 */
class HomePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sidekit_home", Context.MODE_PRIVATE)

    /** Full home order that both the grid and list render from. Default = builtin enum order. */
    val homeOrder: List<String>
        get() = read(KEY_HOME_ORDER, SubApp.entries.map { it.visibilityId() })

    /** Moves [id] by [delta] (positive = later) in the home order, if it is present. */
    fun move(id: String, delta: Int) {
        val order = homeOrder
        val index = order.indexOf(id)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        write(KEY_HOME_ORDER, order.toMutableList().apply { removeAt(index); add(target, id) })
    }

    /**
     * Replaces the stored home order wholesale. Used by the arrange screen's Done, which edits a
     * local buffer and commits once. Unknown ids are dropped so the stored list stays renderable.
     */
    fun saveOrder(order: List<String>) = write(KEY_HOME_ORDER, order.filter { it.isNotBlank() })

    /** Drops the stored order and returns to the built-in default (dock follows the top four). */
    fun reset() {
        prefs.edit().remove(KEY_HOME_ORDER).apply()
    }

    /**
     * The home order restricted to the [known] ids (installed builtin + ported apps), preserving
     * stored order. Ids that are freshly seen (not yet in the stored order) are appended in [known]
     * order, so a new app appears without a manual edit.
     */
    fun homeOrderFor(known: Iterable<String>): List<String> {
        val stored = homeOrder
        val knownSet = known.toSet()
        return stored.filter { it in knownSet } + known.filter { it !in stored }
    }

    /** The dock is derived: the first [DOCK_MAX] apps of the resolved home order. */
    fun dockFor(known: Iterable<String>): List<String> = homeOrderFor(known).take(DOCK_MAX)

    /** True when [id] is one of the first [DOCK_MAX] apps of the stored home order. */
    fun isInDock(id: String): Boolean = homeOrder.indexOf(id) in 0 until DOCK_MAX

    /** Registers a change listener for home order writes; returns an unregister call. */
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_HOME_ORDER || key == null) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Decodes an order-preserving JSON array, dropping blank entries. A missing key returns
     * [default]; malformed data also falls back to [default].
     */
    private fun read(key: String, default: List<String>): List<String> {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
        }.getOrDefault(default)
    }

    /** Encodes as an order-preserving JSON array and writes atomically. */
    private fun write(key: String, value: List<String>) {
        val array = JSONArray().apply { value.forEach { put(it) } }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        /** Maximum number of apps allowed in the dock. */
        const val DOCK_MAX = 4

        private const val KEY_HOME_ORDER = "home_order"
    }
}