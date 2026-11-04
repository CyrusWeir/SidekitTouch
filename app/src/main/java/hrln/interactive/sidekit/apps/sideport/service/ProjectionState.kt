package hrln.interactive.sidekit.apps.sideport.service

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hrln.interactive.sidekit.apps.sideport.interpret.Block
import hrln.interactive.sidekit.apps.sideport.interpret.ScreenModel
import hrln.interactive.sidekit.apps.sideport.model.UiNode
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp

/** What the projection overlay draws. Written on the main thread only. */
@Stable
class ProjectionState(app: PortedApp) {
    var app: PortedApp by mutableStateOf(app)

    /** Null until the app's first screen has been read. */
    var model: ScreenModel? by mutableStateOf(null)

    /** The main list, stitched across scrolls. */
    var items: List<Block> by mutableStateOf(emptyList())

    /** The raw tree, published only while the inspector is open. */
    var tree: UiNode? by mutableStateOf(null)
    var inspector: Boolean by mutableStateOf(false)

    /** A scroll of the real app is under way to fetch more rows. */
    var loadingMore: Boolean by mutableStateOf(false)

    /** Short status line ("Exported to ..."), cleared by tapping it. */
    var notice: String? by mutableStateOf(null)

    /** The screen belongs to another app the ported app opened (a permission dialog, a browser). */
    var foreignPackage: String? by mutableStateOf(null)
}

/** Everything the projected UI can ask of the real app. */
interface ProjectionActions {
    fun press(block: Block, long: Boolean = false)
    fun pressAction(handle: Int, key: String)
    fun setText(fieldKey: String, text: String)
    fun loadMore(forward: Boolean)
    fun back()
    fun exitToSidekit()
    fun showOriginal()
    fun toggleInspector()
    fun exportTree()
    fun openHub()
}
