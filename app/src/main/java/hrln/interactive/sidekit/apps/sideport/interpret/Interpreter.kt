package hrln.interactive.sidekit.apps.sideport.interpret

import hrln.interactive.sidekit.apps.sideport.model.IntRect
import hrln.interactive.sidekit.apps.sideport.model.UiNode
import hrln.interactive.sidekit.apps.sideport.model.WindowSnapshot
import hrln.interactive.sidekit.apps.sideport.recipes.Recipe
import hrln.interactive.sidekit.apps.sideport.recipes.RecipeAction
import hrln.interactive.sidekit.apps.sideport.recipes.Selector

/**
 * Turns an app's accessibility tree into a Sidekit screen: a title, a body of rows and at most
 * two actions beside Back, with everything else behind the Options key.
 *
 * The generic rules (no recipe):
 * - **Title**: the toolbar's text, else the first heading.
 * - **Toolbar icons** and **bottom navigation tabs** become options.
 * - **The main list** is the largest vertical scrollable. Each of its rows becomes one item
 *   (first text the title, the rest the secondary line); rows without anything to press become
 *   text cards (chat messages, articles).
 * - **Text fields** become Sidekit text fields; **switches and checkboxes** become items showing
 *   On/Off.
 * - **Buttons** outside the list compete for the action bar: a floating action button first,
 *   then the last two buttons on screen (dialogs put the positive answer last). Buttons that
 *   don't make it stay in the body as line buttons.
 *
 * A [Recipe] can pick the title, list, row title/secondary and the actions explicitly, and hide
 * subtrees before any of this runs. Pure Kotlin: runs on the projection worker thread.
 */
object Interpreter {
    private const val MAX_BUTTON_LABEL = 28
    private const val MAX_ACTIONS = 2
    private const val MAX_LARGE_TITLE = 32

    fun interpret(snapshot: WindowSnapshot, recipe: Recipe? = null): ScreenModel {
        val root = if (recipe == null || recipe.hide.isEmpty()) snapshot.root
        else snapshot.root.pruned { node -> recipe.hide.any { it.matches(node) } }
        return Pass(snapshot, root, recipe).run()
    }

    /** Colour of a button from what it says. */
    fun toneFor(label: String): Tone {
        val l = label.lowercase().trim()
        return when {
            DESTRUCTIVE.any { l.startsWith(it) } -> Tone.Destructive
            NEGATIVE.any { l == it || l.startsWith("$it ") } -> Tone.Muted
            COMMIT.any { l == it || l.startsWith("$it ") } -> Tone.Secondary
            else -> Tone.Primary
        }
    }

    private val DESTRUCTIVE = listOf("delete", "remove", "uninstall", "block", "report", "erase", "discard", "sign out", "log out")
    private val NEGATIVE = listOf("cancel", "no", "deny", "don't allow", "not now", "skip", "later", "close", "dismiss", "no thanks", "back")
    private val COMMIT = listOf("send", "save", "done", "ok", "allow", "install", "confirm", "accept", "agree", "yes", "submit", "post", "apply")
    private val BACK_LABELS = setOf("navigate up", "back", "up", "close", "go back")

    private class Pass(val snapshot: WindowSnapshot, val root: UiNode, val recipe: Recipe?) {
        val w = snapshot.screenWidth.coerceAtLeast(1)
        val h = snapshot.screenHeight.coerceAtLeast(1)
        val parents = HashMap<Int, UiNode>()
        val header = ArrayList<Block>()
        val items = ArrayList<Block>()
        val footer = ArrayList<Block>()
        val keys = HashMap<String, Int>()
        val buttons = ArrayList<ButtonBlock>()
        val consumed = HashSet<Int>()
        var unlabelled = 0
        val extents = HashMap<Int, IntRect>()
        var seenList = false

        init {
            fun index(n: UiNode) { n.children.forEach { parents[it.handle] = n; index(it) } }
            index(root)
        }

        fun run(): ScreenModel {
            val toolbar = root.find(::isToolbar)
            val bottomNav = root.find(::isBottomNav)
            val list = recipe?.list?.let { sel -> root.find(sel::matches) }?.let(::scrollAncestorOrSelf) ?: mainList()
            val title = recipeTitle() ?: toolbar?.let(::toolbarTitle) ?: root.find { it.heading && !it.label.isNullOrBlank() }?.label

            val options = ArrayList<ActionSpec>()
            toolbar?.let { tb -> toolbarOptions(tb, title).forEach { options += it; consumed += it.handle } }
            bottomNav?.let { nav -> tabs(nav).forEach { options += it; consumed += it.handle } }

            // Recipe actions are claimed before the walk so they don't also appear as rows.
            val recipeActions = recipe?.actions.orEmpty().mapNotNull(::resolve)
            val recipeOptions = recipe?.options.orEmpty().mapNotNull(::resolve)
            (recipeActions + recipeOptions).forEach { consumed += it.handle }
            val fab = if (recipeActions.isEmpty()) root.find { it.simpleClass.contains("FloatingActionButton") && it.handle !in consumed } else null
            fab?.let { consumed += it.handle }

            emit(root, toolbar, bottomNav, list)
            val shown = title ?: largeTitle()
            if (shown != null) dropFirstTitleEcho(shown)

            val actions: List<ActionSpec>
            if (recipeActions.isNotEmpty()) {
                actions = recipeActions.take(MAX_ACTIONS)
                options.addAll(0, recipeActions.drop(MAX_ACTIONS))
            } else {
                val chosen = pickButtons(fab)
                actions = chosen.map { ActionSpec(it.key, it.label, it.handle, it.tone) }
                val chosenKeys = chosen.map { it.key }.toSet()
                listOf(header, items, footer).forEach { region -> region.removeAll { it is ButtonBlock && it.key in chosenKeys } }
            }
            options += recipeOptions

            return ScreenModel(
                packageName = snapshot.packageName,
                title = shown,
                header = header,
                items = items,
                footer = footer,
                list = list?.let { ListRegion(it.handle, it.canScrollForward, it.canScrollBackward) },
                actions = actions,
                options = options.distinctBy { it.handle },
                signature = "${snapshot.packageName}|${snapshot.screenName.orEmpty()}|${title.orEmpty()}",
                recipe = recipe?.name,
                unlabelled = unlabelled,
            )
        }

        // ---- Regions ----------------------------------------------------------------------

        fun isToolbar(n: UiNode): Boolean {
            if (n.bounds.top > h / 4) return false
            val id = n.idName.orEmpty()
            return n.simpleClass.endsWith("Toolbar") || n.simpleClass == "ActionBarContainer" ||
                id.contains("toolbar") || id == "action_bar" || id == "app_bar"
        }

        fun isBottomNav(n: UiNode): Boolean {
            val box = extent(n)
            if (box.top < h * 3 / 4 || box.area == 0L || n.scrollable || n.collection) return false
            val cls = n.simpleClass
            if (cls.contains("BottomNavigation") || cls == "NavigationBarView" || cls.contains("TabLayout")) return true
            if (n.idName.orEmpty().contains("bottom_nav")) return true
            // Compose and custom bars: 3-6 labelled, pressable siblings side by side in one band.
            val tabs = n.children.filter { it.clickable && it.texts().isNotEmpty() }
            if (tabs.size !in 3..6 || tabs.size != n.children.size || n.children.any { it.editable }) return false
            val rows = tabs.map { it.bounds.centerY }
            val lefts = tabs.map { it.bounds.left }
            return rows.max() - rows.min() <= h / 20 && lefts.zipWithNext().all { (a, b) -> b > a }
        }

        /**
         * The largest vertical scrollable, measured by everything inside it: Android can report a
         * container's bounds as just the clipped sliver at the screen edge while its rows are fully
         * visible. Carousels (sideways only) and narrow side panes don't count.
         */
        fun mainList(): UiNode? = root.filterTree { it.scrollable && !it.horizontalOnly }
            .map { it to extent(it) }
            .filter { (_, box) -> box.width >= w / 3 }
            .maxByOrNull { (_, box) -> box.area }?.first

        /** Bounds of [n] together with all of its descendants. */
        fun extent(n: UiNode): IntRect {
            extents[n.handle]?.let { return it }
            var l = n.bounds.left; var t = n.bounds.top; var r = n.bounds.right; var b = n.bounds.bottom
            if (n.bounds.area == 0L) { l = Int.MAX_VALUE; t = Int.MAX_VALUE; r = Int.MIN_VALUE; b = Int.MIN_VALUE }
            n.children.forEach { c ->
                val e = extent(c)
                if (e.area > 0) { l = minOf(l, e.left); t = minOf(t, e.top); r = maxOf(r, e.right); b = maxOf(b, e.bottom) }
            }
            val box = if (l > r || t > b) IntRect.Empty else IntRect(l, t, r, b)
            extents[n.handle] = box
            return box
        }

        fun scrollAncestorOrSelf(n: UiNode): UiNode {
            var cur: UiNode? = n
            while (cur != null) { if (cur.scrollable) return cur; cur = parents[cur.handle] }
            return n
        }

        fun recipeTitle(): String? = recipe?.title?.let { sel -> root.find(sel::matches) }?.let { it.label ?: it.texts().firstOrNull() }

        fun toolbarTitle(tb: UiNode): String? =
            tb.find { it !== tb && !it.clickable && !it.text.isNullOrBlank() }?.text?.trim()

        fun toolbarOptions(tb: UiNode, title: String?): List<ActionSpec> = tb.filterTree { it !== tb && it.clickable }
            .mapNotNull { n ->
                val label = n.label ?: n.texts().firstOrNull() ?: return@mapNotNull null
                if (label.lowercase() in BACK_LABELS || label == title) return@mapNotNull null
                ActionSpec("tb.${n.viewId ?: label}", label, n.handle, Tone.Muted)
            }

        fun tabs(nav: UiNode): List<ActionSpec> = nav.filterTree { it !== nav && it.clickable }
            .mapNotNull { n ->
                val label = n.label ?: n.texts().firstOrNull() ?: return@mapNotNull null
                ActionSpec("tab.$label", label, n.handle, if (n.selected) Tone.Primary else Tone.Muted, selected = n.selected)
            }

        fun resolve(a: RecipeAction): ActionSpec? {
            val node = root.find(a.selector::matches) ?: return null
            val target = clickableAncestorOrSelf(node) ?: return null
            val label = a.label ?: node.label ?: target.texts().firstOrNull() ?: return null
            return ActionSpec("r.${a.selector}", label, target.handle, a.tone ?: toneFor(label))
        }

        fun clickableAncestorOrSelf(n: UiNode): UiNode? {
            var cur: UiNode? = n
            while (cur != null) { if (cur.clickable) return cur; cur = parents[cur.handle] }
            return null
        }

        // ---- Body -------------------------------------------------------------------------

        fun sink(inList: Boolean): MutableList<Block> = when {
            inList -> items
            seenList -> footer
            else -> header
        }

        fun key(kind: String, n: UiNode, text: String): String {
            val base = "$kind:${n.viewId.orEmpty()}:${text.take(80)}"
            val count = keys.merge(base, 1, Int::plus)!!
            return if (count == 1) base else "$base#$count"
        }

        fun emit(n: UiNode, toolbar: UiNode?, bottomNav: UiNode?, list: UiNode?, inList: Boolean = false) {
            if (n === toolbar || n === bottomNav || n.handle in consumed) return
            if (n === list) {
                seenList = true
                rows(n).forEach { row -> emitRow(row, toolbar, bottomNav) }
                return
            }
            val out = sink(inList)
            when {
                n.editable -> {
                    out += FieldBlock(key("field", n, n.hint ?: n.description.orEmpty()), n.handle, if (n.password) "" else n.text.orEmpty(), n.hint ?: n.description, n.password)
                    return
                }
                (n.clickable || n.checkable) && !isLayoutShell(n) -> { emitPressable(n, out, inList); return }
                !n.text.isNullOrBlank() -> out += TextBlock(key("text", n, n.text), n.text.trim(), heading = n.heading, inList = inList)
            }
            n.children.forEach { emit(it, toolbar, bottomNav, list, inList) }
        }

        /** The list's rows, looking through single-child wrappers (ScrollView > LinearLayout > rows). */
        fun rows(list: UiNode): List<UiNode> {
            var cur = list
            while (cur.children.size == 1 && !cur.children[0].clickable && !cur.children[0].editable && cur.children[0].text.isNullOrBlank()) cur = cur.children[0]
            return cur.children
        }

        fun emitRow(row: UiNode, toolbar: UiNode?, bottomNav: UiNode?) {
            if (row.handle in consumed) return
            val interactive = row.find { it.clickable || it.editable || it.checkable } != null
            if (!interactive) {
                // Non-interactive row: one card (a chat message, a paragraph, a section header).
                val texts = row.texts()
                if (texts.isEmpty()) return
                val heading = texts.size == 1 && (row.heading || row.find { it.heading } != null)
                items += TextBlock(key("row", row, texts.joinToString("|")), texts.first(), heading, texts.drop(1).joinToString(" · ").ifEmpty { null }, inList = true)
                return
            }
            emit(row, toolbar, bottomNav, null, inList = true)
        }

        /**
         * Some apps make a whole page one clickable layout. Treat a pressable node as a single row
         * only if it doesn't hold several other controls and isn't most of the screen.
         */
        fun isLayoutShell(n: UiNode): Boolean {
            if (n.bounds.area < (w.toLong() * h) * 2 / 5) return false
            var controls = 0
            n.walk { if (it !== n && (it.clickable || it.editable)) controls++ }
            return controls >= 2
        }

        fun emitPressable(n: UiNode, out: MutableList<Block>, inList: Boolean) {
            val titleNode = recipe?.itemTitle?.let { sel -> n.find(sel::matches) }
            val secondaryNode = recipe?.itemSecondary?.let { sel -> n.find(sel::matches) }
            val texts = n.texts()
            val title = titleNode?.label ?: texts.firstOrNull()
            if (title == null) { unlabelled++; return }
            val checkNode = if (n.checkable) n else n.find { it.checkable }
            val buttonLike = !inList && checkNode == null && texts.size == 1 && title.length <= MAX_BUTTON_LABEL &&
                (n.simpleClass.contains("Button") || n.idName.orEmpty().startsWith("button"))
            if (buttonLike) {
                val block = ButtonBlock(key("button", n, title), title, n.handle, toneFor(title))
                buttons += block
                out += block
                return
            }
            val secondary = secondaryNode?.label ?: texts.filter { it != title }.joinToString(" · ").ifEmpty { null }
            out += ItemBlock(
                key = key("item", n, texts.joinToString("|")),
                title = title,
                secondary = secondary,
                click = when {
                    n.clickable -> n.handle
                    checkNode?.clickable == true -> checkNode.handle
                    else -> null
                },
                longClick = n.handle.takeIf { n.longClickable },
                checked = checkNode?.checked,
                selected = n.selected,
                enabled = n.enabled,
                inList = inList,
            )
        }

        /**
         * No toolbar or heading: a short line of text opening the page in its top half is the
         * page's own large title (collapsing toolbars, Settings-style home pages).
         */
        fun largeTitle(): String? {
            val first = header.firstOrNull() as? TextBlock ?: return null
            if (first.text.length > MAX_LARGE_TITLE || first.text.contains('\n')) return null
            val node = root.find { !it.text.isNullOrBlank() && it.text.trim() == first.text } ?: return null
            return first.text.takeIf { node.bounds.top < h / 2 }
        }

        fun dropFirstTitleEcho(title: String) {
            val echo = header.firstOrNull { it is TextBlock && it.text == title } ?: return
            header.remove(echo)
        }

        /** FAB first, else the last [MAX_ACTIONS] buttons outside the list, negative answer on the left. */
        fun pickButtons(fab: UiNode?): List<ButtonBlock> {
            val pool = buttons
            val chosen = ArrayList<ButtonBlock>()
            if (fab != null) {
                val label = fab.label ?: fab.texts().firstOrNull()
                if (label != null) chosen += ButtonBlock(key("fab", fab, label), label, fab.handle, Tone.Primary)
            }
            chosen += pool.takeLast(MAX_ACTIONS - chosen.size)
            return chosen.sortedBy { if (it.tone == Tone.Muted) 0 else 1 }
        }
    }
}
