package hrln.interactive.sidekit.apps.sideport.recipes

import hrln.interactive.sidekit.apps.sideport.interpret.Tone
import hrln.interactive.sidekit.apps.sideport.model.UiNode
import hrln.interactive.sidekit.apps.sideport.model.WindowSnapshot

/**
 * Matches nodes of a projected app. Written as `kind:value`, several joined with ` & `:
 *
 * - `id:send` matches a view id ending in `/send`; `id:com.app:id/send` matches it exactly.
 * - `text:Allow` matches text (or content description) equal to "Allow", ignoring case;
 *   `text~:allow` matches text containing it.
 * - `desc:More options`, `desc~:more` the same, on the content description only.
 * - `class:EditText` matches a class name ending in `EditText`.
 */
class Selector private constructor(private val source: String, private val tests: List<(UiNode) -> Boolean>) {
    fun matches(node: UiNode): Boolean = tests.all { it(node) }

    override fun toString() = source

    companion object {
        fun parse(source: String): Selector {
            val parts = source.split(" & ").map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "Empty selector" }
            return Selector(source, parts.map(::condition))
        }

        private fun condition(part: String): (UiNode) -> Boolean {
            val colon = part.indexOf(':')
            require(colon > 0) { "Selector '$part' needs kind:value" }
            val kind = part.substring(0, colon)
            val value = part.substring(colon + 1)
            return when (kind) {
                "id" -> if (value.contains('/')) { n -> n.viewId == value } else { n -> n.idName == value }
                "text" -> { n -> n.text?.trim().equals(value, true) || (n.text.isNullOrBlank() && n.description?.trim().equals(value, true)) }
                "text~" -> { n -> (n.text ?: n.description)?.contains(value, true) == true }
                "desc" -> { n -> n.description?.trim().equals(value, true) }
                "desc~" -> { n -> n.description?.contains(value, true) == true }
                "class" -> { n -> n.className.endsWith(value) }
                else -> throw IllegalArgumentException("Unknown selector kind '$kind' in '$part'")
            }
        }
    }
}

/** A button the recipe promotes to the action bar (or the Options bar), optionally relabelled. */
data class RecipeAction(val selector: Selector, val label: String? = null, val tone: Tone? = null)

/**
 * Per-app rules that shape how a projected screen is drawn, layered over the generic
 * interpreter. Recipes live as JSON in `assets/sideport/recipes/` and, for writing new ones on
 * the phone, in `Android/data/<sidekit>/files/sideport/recipes/` (see [RecipeJson]).
 */
data class Recipe(
    val name: String,
    /** Package names this recipe applies to; `*` applies to every app. */
    val packages: List<String>,
    /** Every selector must match some node for the recipe to apply to a screen. */
    val whenPresent: List<Selector> = emptyList(),
    /** Screen (activity) name must contain this, if set. */
    val screen: String? = null,
    val title: Selector? = null,
    /** Subtrees to drop before interpreting (ads, decorative headers, duplicate controls). */
    val hide: List<Selector> = emptyList(),
    /** The main scrolling list, if the largest scrollable isn't it. */
    val list: Selector? = null,
    /** Within each list row: which node is the title and which the secondary line. */
    val itemTitle: Selector? = null,
    val itemSecondary: Selector? = null,
    /** In order; the first two shown found on the screen go in the action bar. */
    val actions: List<RecipeAction> = emptyList(),
    val options: List<RecipeAction> = emptyList(),
    val priority: Int = 0,
) {
    val global: Boolean get() = "*" in packages

    fun appliesTo(snapshot: WindowSnapshot): Boolean {
        if (!global && snapshot.packageName !in packages) return false
        if (screen != null && snapshot.screenName?.contains(screen) != true) return false
        return whenPresent.all { sel -> snapshot.root.find(sel::matches) != null }
    }
}

/** The installed recipes. App-specific recipes win over global ones, then higher [Recipe.priority]. */
class RecipeBook(val recipes: List<Recipe>) {
    fun forScreen(snapshot: WindowSnapshot): Recipe? =
        recipes.filter { it.appliesTo(snapshot) }
            .sortedWith(compareBy<Recipe> { it.global }.thenByDescending { it.priority }.thenByDescending { it.whenPresent.size })
            .firstOrNull()

    companion object {
        val Empty = RecipeBook(emptyList())
    }
}
