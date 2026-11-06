package hrln.interactive.sidekit.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hrln.interactive.sidekit.apps.settings.visibilityId
import hrln.interactive.sidekit.navigation.SubApp

/**
 * The Edit buffer for the "Arrange apps" screen.
 *
 * Seeds the home order from the stored [HomePreferences], then lets the user edit a local copy so
 * nothing is persisted until [commit] (Back or Reset only touch this buffer). [order] and
 * [selected] are Compose state so the arrange UI re-renders as the user edits.
 *
 * Reordering is a selection mode: pressing Confirm on a focused app selects it ([selected]), then
 * Up/Down moves it through the order (or each row's arrows move that app directly). The dock is
 * never edited by hand — it is derived as the first [HomePreferences.DOCK_MAX] apps of [order],
 * so moving an app into/out of the top four automatically pins/unpins it.
 */
class HomeArranger(private val home: HomePreferences, known: Iterable<String>) {

    private val knownIds = known.toList()
    private val builtinIds = SubApp.entries.map { it.visibilityId() }.toSet()

    /** Ported (or otherwise freshly-seen) ids that aren't part of the built-in default list. */
    private val appendedDefaults: List<String> = knownIds.filter { it !in builtinIds }

    var order by mutableStateOf(home.homeOrderFor(knownIds))
        private set

    /** The app currently selected for reordering, or null when not in selection mode. */
    var selected by mutableStateOf<String?>(null)
        private set

    /** The dock: the first [HomePreferences.DOCK_MAX] apps of [order]. */
    val dock: List<String> get() = order.take(HomePreferences.DOCK_MAX)

    fun isInDock(id: String): Boolean = order.indexOf(id) in 0 until HomePreferences.DOCK_MAX

    fun isSelected(id: String): Boolean = selected == id

    /** Selects [id] (or deselects it if it is already selected). */
    fun select(id: String) {
        selected = if (selected == id) null else id
    }

    /** Moves [id] by [delta] in the home order (positive = later), clamped at the ends. */
    fun move(id: String, delta: Int) {
        val index = order.indexOf(id)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order = order.toMutableList().apply { removeAt(index); add(target, id) }
    }

    /** Moves the currently selected app by [delta]; no-op when nothing is selected. */
    fun moveSelected(delta: Int) {
        selected?.let { move(it, delta) }
    }

    /** Restores the built-in defaults (enum order + freshly-seen apps) and clears the selection. */
    fun reset() {
        order = SubApp.entries.map { it.visibilityId() }.filter { it in knownIds } + appendedDefaults
        selected = null
    }

    /** Writes the edited order to [HomePreferences]. The dock follows automatically. */
    fun commit() {
        home.saveOrder(order)
    }
}