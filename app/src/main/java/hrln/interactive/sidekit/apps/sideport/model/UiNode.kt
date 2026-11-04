package hrln.interactive.sidekit.apps.sideport.model

/** Screen rectangle in physical pixels (a plain copy of [android.graphics.Rect], so the model is pure Kotlin). */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = if (width <= 0 || height <= 0) 0L else width.toLong() * height
    val centerY: Int get() = (top + bottom) / 2
    fun contains(other: IntRect) = other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    companion object {
        val Empty = IntRect(0, 0, 0, 0)
    }
}

/**
 * An immutable copy of one accessibility node of a projected app.
 *
 * The projection service reads the live [android.view.accessibility.AccessibilityNodeInfo] tree
 * once per change and copies the fields it uses into this tree, so interpretation, recipes and
 * list stitching are plain Kotlin (testable on the JVM) and never make binder calls. [handle]
 * points back at the live node in the service, which is what actions are performed on.
 */
data class UiNode(
    val handle: Int,
    val className: String = "",
    val viewId: String? = null,
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val bounds: IntRect = IntRect.Empty,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val canScrollForward: Boolean = false,
    val canScrollBackward: Boolean = false,
    /** Scrolls only sideways (reports left/right scroll actions but not up/down): a carousel. */
    val horizontalOnly: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val password: Boolean = false,
    val heading: Boolean = false,
    /** The node reports collection info (RecyclerView, ListView, LazyColumn, ...). */
    val collection: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    /** Short class name: `android.widget.Button` -> `Button`. */
    val simpleClass: String get() = className.substringAfterLast('.')

    /** View id without its package: `com.app:id/send` -> `send`. */
    val idName: String? get() = viewId?.substringAfterLast('/')

    /** What a person would read for this node alone. */
    val label: String? get() = text?.trim()?.takeIf { it.isNotEmpty() } ?: description?.trim()?.takeIf { it.isNotEmpty() }

    fun walk(visit: (UiNode) -> Unit) {
        visit(this)
        children.forEach { it.walk(visit) }
    }

    fun find(predicate: (UiNode) -> Boolean): UiNode? {
        if (predicate(this)) return this
        children.forEach { child -> child.find(predicate)?.let { return it } }
        return null
    }

    fun filterTree(predicate: (UiNode) -> Boolean): List<UiNode> {
        val out = ArrayList<UiNode>()
        walk { if (predicate(it)) out.add(it) }
        return out
    }

    /** This tree without the subtrees [drop] matches. */
    fun pruned(drop: (UiNode) -> Boolean): UiNode =
        copy(children = children.filterNot(drop).map { it.pruned(drop) })

    /** All readable strings in this subtree, in reading order, without repeats. */
    fun texts(): List<String> {
        val out = LinkedHashSet<String>()
        walk { node ->
            val t = node.text?.trim()
            if (!t.isNullOrEmpty()) out.add(t)
            else if (node !== this && !node.description.isNullOrBlank() && node.children.isEmpty() && !node.editable) out.add(node.description.trim())
        }
        if (out.isEmpty()) description?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        return out.toList()
    }
}

/** One read of the projected app's top window. */
data class WindowSnapshot(
    val packageName: String,
    /** Activity class name from the last window-state event, or the window title. */
    val screenName: String?,
    val root: UiNode,
    val screenWidth: Int,
    val screenHeight: Int,
)
