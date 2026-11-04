package hrln.interactive.sidekit.apps.sideport.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import hrln.interactive.sidekit.apps.sideport.model.IntRect
import hrln.interactive.sidekit.apps.sideport.model.UiNode

/**
 * Copies a live accessibility tree into [UiNode]s, keeping the live nodes by handle so actions can
 * be performed on them later.
 *
 * Every getter on an [AccessibilityNodeInfo] child is a binder call into the app, so this runs on
 * the projection worker thread, reads each node once, skips subtrees the user can't see and stops
 * at [MAX_NODES]: a pathological screen degrades to a partial projection instead of a stall.
 */
class NodeReader {
    /** Live nodes of the last read, by [UiNode.handle]. */
    private var live = HashMap<Int, AccessibilityNodeInfo>()

    /** Handle of each node seen in the last read (nodes are equal when they're the same view). */
    private var handles = HashMap<AccessibilityNodeInfo, Int>()
    private var nextHandles = HashMap<AccessibilityNodeInfo, Int>()
    private var nextHandle = 1
    private var count = 0
    private val rect = Rect()

    fun node(handle: Int): AccessibilityNodeInfo? = live[handle]

    fun read(root: AccessibilityNodeInfo): UiNode? {
        // The same view keeps its handle across reads, so an unchanged screen reads as an equal
        // model and nothing recomposes. A recycled list row keeps its view's handle while showing
        // other content, so callers act on rows by their key in the latest model, not by a handle
        // kept from an older one.
        live = HashMap(256)
        nextHandles = HashMap(256)
        count = 0
        val tree = copy(root, 0)
        handles = nextHandles
        return tree
    }

    private fun copy(info: AccessibilityNodeInfo, depth: Int): UiNode? {
        if (!info.isVisibleToUser || count >= MAX_NODES || depth > MAX_DEPTH) return null
        count++
        val handle = handles[info] ?: nextHandle++
        nextHandles[info] = handle
        live[handle] = info
        info.getBoundsInScreen(rect)
        val actions = info.actionList
        val children = ArrayList<UiNode>(info.childCount)
        for (i in 0 until info.childCount) {
            val child = info.getChild(i) ?: continue
            copy(child, depth + 1)?.let(children::add)
        }
        return UiNode(
            handle = handle,
            className = info.className?.toString().orEmpty(),
            viewId = info.viewIdResourceName,
            text = info.text?.toString(),
            description = info.contentDescription?.toString(),
            hint = info.hintText?.toString(),
            bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom),
            clickable = info.isClickable && info.isEnabled,
            longClickable = info.isLongClickable && info.isEnabled,
            editable = info.isEditable,
            scrollable = info.isScrollable,
            canScrollForward = actions.contains(AccessibilityAction.ACTION_SCROLL_FORWARD),
            canScrollBackward = actions.contains(AccessibilityAction.ACTION_SCROLL_BACKWARD),
            horizontalOnly = (actions.contains(AccessibilityAction.ACTION_SCROLL_LEFT) || actions.contains(AccessibilityAction.ACTION_SCROLL_RIGHT)) &&
                !actions.contains(AccessibilityAction.ACTION_SCROLL_UP) && !actions.contains(AccessibilityAction.ACTION_SCROLL_DOWN),
            checkable = info.isCheckable,
            checked = info.isChecked,
            selected = info.isSelected,
            enabled = info.isEnabled,
            focused = info.isFocused,
            password = info.isPassword,
            heading = info.isHeading,
            collection = info.collectionInfo != null,
            children = children,
        )
    }

    companion object {
        const val MAX_NODES = 700
        const val MAX_DEPTH = 48
    }
}
