package hrln.interactive.sidekit.apps.sideport.service

import hrln.interactive.sidekit.apps.sideport.model.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Inspector export: a projected screen's tree as JSON, the raw material for writing a recipe.
 * Only non-default fields are written, so dumps stay readable.
 */
object TreeJson {
    fun write(root: UiNode, packageName: String?, screen: String?): JSONObject =
        JSONObject().put("package", packageName).put("screen", screen).put("root", node(root))

    private fun node(n: UiNode): JSONObject = JSONObject().apply {
        put("class", n.className)
        n.viewId?.let { put("id", it) }
        n.text?.let { put("text", it) }
        n.description?.let { put("desc", it) }
        n.hint?.let { put("hint", it) }
        put("bounds", "${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
        val flags = buildList {
            if (n.clickable) add("click")
            if (n.longClickable) add("long")
            if (n.editable) add("edit")
            if (n.scrollable) add("scroll")
            if (n.checkable) add(if (n.checked) "checked" else "unchecked")
            if (n.selected) add("selected")
            if (!n.enabled) add("disabled")
            if (n.password) add("password")
            if (n.heading) add("heading")
            if (n.collection) add("collection")
        }
        if (flags.isNotEmpty()) put("flags", flags.joinToString(","))
        if (n.children.isNotEmpty()) put("children", JSONArray().apply { n.children.forEach { put(node(it)) } })
    }
}
