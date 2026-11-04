package hrln.interactive.sidekit.apps.sideport.interpret

/** Colour role of a projected action, mapped to [hrln.interactive.sidekit.design.action.ActionTone]. */
enum class Tone { Primary, Secondary, Destructive, Muted }

/** A button of the projected app, shown in the Sidekit action bar or the Options bar. */
data class ActionSpec(
    val key: String,
    val label: String,
    val handle: Int,
    val tone: Tone = Tone.Primary,
    /** Bottom-navigation tab that is currently showing. */
    val selected: Boolean = false,
)

/** One row of the projected body. [key] is stable across reads while the row shows the same thing. */
sealed interface Block {
    val key: String
}

/** Read-only content: a paragraph, a heading, or a non-interactive list row (a chat message, say). */
data class TextBlock(
    override val key: String,
    val text: String,
    val heading: Boolean = false,
    /** Smaller line under the text (a message's time, a row's metadata). */
    val detail: String? = null,
    val inList: Boolean = false,
) : Block

data class ItemBlock(
    override val key: String,
    val title: String,
    val secondary: String? = null,
    val click: Int? = null,
    val longClick: Int? = null,
    /** Non-null for switches and checkboxes. */
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** Part of the main scrolling list (stitched across scrolls), not the fixed part of the screen. */
    val inList: Boolean = false,
) : Block

data class FieldBlock(
    override val key: String,
    val handle: Int,
    val text: String,
    val hint: String?,
    val password: Boolean = false,
) : Block

/** A button that didn't earn a place in the action bar. */
data class ButtonBlock(override val key: String, val label: String, val handle: Int, val tone: Tone = Tone.Primary) : Block

/** The app's main scrolling container. */
data class ListRegion(val handle: Int, val canScrollForward: Boolean, val canScrollBackward: Boolean)

/** A projected screen, ready to draw with Sidekit components. */
data class ScreenModel(
    val packageName: String,
    val title: String?,
    /** Rows above the main list (search fields, headers, filters). */
    val header: List<Block>,
    /** Rows of the main scrolling list as currently visible in the app. */
    val items: List<Block>,
    /** Rows below the main list. */
    val footer: List<Block>,
    val list: ListRegion?,
    /** At most two, shown beside Back. */
    val actions: List<ActionSpec>,
    /** Toolbar icons, tabs and spare buttons, shown by the Options key. */
    val options: List<ActionSpec>,
    /** Identifies "the same screen" across reads, so the stitched list resets when it changes. */
    val signature: String,
    /** Recipe that shaped this screen, if any. */
    val recipe: String? = null,
    /** Nodes that could be clicked but had nothing to read (icons without descriptions). */
    val unlabelled: Int = 0,
) {
    val hasFields: Boolean get() = (header + items + footer).any { it is FieldBlock }
    val isEmpty: Boolean get() = header.isEmpty() && items.isEmpty() && footer.isEmpty() && actions.isEmpty()
}
