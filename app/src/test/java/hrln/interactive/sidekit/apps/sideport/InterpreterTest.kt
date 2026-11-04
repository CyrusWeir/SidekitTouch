package hrln.interactive.sidekit.apps.sideport

import hrln.interactive.sidekit.apps.sideport.interpret.ButtonBlock
import hrln.interactive.sidekit.apps.sideport.interpret.FieldBlock
import hrln.interactive.sidekit.apps.sideport.interpret.Interpreter
import hrln.interactive.sidekit.apps.sideport.interpret.ItemBlock
import hrln.interactive.sidekit.apps.sideport.interpret.TextBlock
import hrln.interactive.sidekit.apps.sideport.interpret.Tone
import hrln.interactive.sidekit.apps.sideport.model.IntRect
import hrln.interactive.sidekit.apps.sideport.model.UiNode
import hrln.interactive.sidekit.apps.sideport.model.WindowSnapshot
import hrln.interactive.sidekit.apps.sideport.recipes.Recipe
import hrln.interactive.sidekit.apps.sideport.recipes.RecipeAction
import hrln.interactive.sidekit.apps.sideport.recipes.RecipeBook
import hrln.interactive.sidekit.apps.sideport.recipes.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds trees the way NodeReader would, with sequential handles. */
private class Tree {
    private var next = 1
    fun n(
        cls: String = "android.view.View",
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        bounds: IntRect = IntRect(0, 0, 480, 100),
        click: Boolean = false,
        edit: Boolean = false,
        scroll: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        selected: Boolean = false,
        heading: Boolean = false,
        fwd: Boolean = false,
        vararg children: UiNode,
    ) = UiNode(
        handle = next++, className = cls, viewId = id, text = text, description = desc, bounds = bounds,
        clickable = click, editable = edit, scrollable = scroll, canScrollForward = fwd, checkable = checkable,
        checked = checked, selected = selected, heading = heading, children = children.toList(),
    )
}

private fun snap(root: UiNode, pkg: String = "com.example") = WindowSnapshot(pkg, "com.example.MainActivity", root, 480, 640)

class InterpreterTest {
    @Test fun `toolbar title, list rows, toolbar icons and fab`() {
        val t = Tree()
        val root = t.n(
            bounds = IntRect(0, 0, 480, 640),
            children = arrayOf(
                t.n("androidx.appcompat.widget.Toolbar", id = "com.example:id/toolbar", bounds = IntRect(0, 0, 480, 60), children = arrayOf(
                    t.n("android.widget.ImageButton", desc = "Navigate up", click = true, bounds = IntRect(0, 0, 60, 60)),
                    t.n("android.widget.TextView", text = "Inbox", bounds = IntRect(60, 0, 300, 60)),
                    t.n("android.widget.ImageButton", desc = "Search", click = true, bounds = IntRect(400, 0, 460, 60)),
                )),
                t.n("androidx.recyclerview.widget.RecyclerView", id = "com.example:id/list", scroll = true, fwd = true, bounds = IntRect(0, 60, 480, 640), children = arrayOf(
                    t.n(click = true, bounds = IntRect(0, 60, 480, 160), children = arrayOf(
                        t.n("android.widget.TextView", text = "Alice"),
                        t.n("android.widget.TextView", text = "Lunch tomorrow?"),
                    )),
                    t.n(click = true, bounds = IntRect(0, 160, 480, 260), children = arrayOf(
                        t.n("android.widget.TextView", text = "Bob"),
                        t.n("android.widget.TextView", text = "Sent a photo"),
                    )),
                )),
                t.n("com.google.android.material.floatingactionbutton.FloatingActionButton", desc = "Compose", click = true, bounds = IntRect(380, 540, 460, 620)),
            ),
        )
        val m = Interpreter.interpret(snap(root))
        assertEquals("Inbox", m.title)
        assertEquals(listOf("Alice", "Bob"), m.items.map { (it as ItemBlock).title })
        assertEquals("Lunch tomorrow?", (m.items[0] as ItemBlock).secondary)
        assertTrue(m.items.all { (it as ItemBlock).inList })
        assertEquals(listOf("Compose"), m.actions.map { it.label })
        // "Navigate up" is Back, not an option.
        assertEquals(listOf("Search"), m.options.map { it.label })
        assertTrue(m.list!!.canScrollForward)
        assertTrue(m.header.isEmpty())
    }

    @Test fun `form fields, switches and buttons`() {
        val t = Tree()
        val root = t.n(
            bounds = IntRect(0, 0, 480, 640),
            children = arrayOf(
                t.n("android.widget.TextView", text = "Sign in", heading = true, bounds = IntRect(0, 0, 480, 80)),
                t.n("android.widget.EditText", id = "com.example:id/email", edit = true, bounds = IntRect(0, 100, 480, 160)).copy(hint = "Email"),
                t.n("android.widget.EditText", id = "com.example:id/password", edit = true, bounds = IntRect(0, 170, 480, 230)).copy(hint = "Password", password = true, text = "••••"),
                t.n(click = true, bounds = IntRect(0, 240, 480, 300), children = arrayOf(
                    t.n("android.widget.TextView", text = "Remember me"),
                    t.n("android.widget.Switch", checkable = true, checked = true),
                )),
                t.n("android.widget.Button", text = "Forgot password", click = true, bounds = IntRect(0, 400, 480, 460)),
                t.n("android.widget.Button", text = "Cancel", click = true, bounds = IntRect(0, 500, 240, 560)),
                t.n("android.widget.Button", text = "Sign in", click = true, bounds = IntRect(240, 500, 480, 560)),
            ),
        )
        val m = Interpreter.interpret(snap(root))
        assertEquals("Sign in", m.title)
        val fields = m.header.filterIsInstance<FieldBlock>()
        assertEquals(listOf("Email", "Password"), fields.map { it.hint })
        assertEquals("", fields[1].text) // never echo a password field's mask
        assertTrue(m.hasFields)
        val toggle = m.header.filterIsInstance<ItemBlock>().single()
        assertEquals("Remember me", toggle.title)
        assertEquals(true, toggle.checked)
        // Last two buttons take the bar, the negative one on the left; the rest stay as rows.
        assertEquals(listOf("Cancel" to Tone.Muted, "Sign in" to Tone.Primary), m.actions.map { it.label to it.tone })
        assertEquals(listOf("Forgot password"), m.header.filterIsInstance<ButtonBlock>().map { it.label })
        // The heading was used as the title, so it isn't repeated in the body.
        assertTrue(m.header.none { it is TextBlock && it.text == "Sign in" })
    }

    @Test fun `non-interactive list rows become text cards`() {
        val t = Tree()
        val root = t.n(bounds = IntRect(0, 0, 480, 640), children = arrayOf(
            t.n("android.widget.ListView", scroll = true, bounds = IntRect(0, 0, 480, 560), children = arrayOf(
                t.n(children = arrayOf(t.n(text = "Hi there"), t.n(text = "10:30"))),
                t.n(children = arrayOf(t.n(text = "Hello!"), t.n(text = "10:31"))),
            )),
            t.n("android.widget.EditText", edit = true, bounds = IntRect(0, 560, 400, 640)).copy(hint = "Message"),
            t.n("android.widget.ImageButton", desc = "Send", click = true, bounds = IntRect(400, 560, 480, 640)),
        ))
        val m = Interpreter.interpret(snap(root))
        val messages = m.items.filterIsInstance<TextBlock>()
        assertEquals(listOf("Hi there", "Hello!"), messages.map { it.text })
        assertEquals("10:30", messages[0].detail)
        assertEquals("Message", (m.footer.first() as FieldBlock).hint)
        // An icon button named only by its description still makes the action bar.
        assertEquals(listOf("Send"), m.actions.map { it.label })
    }

    @Test fun `bottom navigation becomes options with the selected tab marked`() {
        val t = Tree()
        val root = t.n(bounds = IntRect(0, 0, 480, 640), children = arrayOf(
            t.n(text = "Feed", bounds = IntRect(0, 100, 480, 200)),
            t.n("com.google.android.material.bottomnavigation.BottomNavigationView", bounds = IntRect(0, 560, 480, 640), children = arrayOf(
                t.n(desc = "Home", click = true, selected = true, bounds = IntRect(0, 560, 160, 640)),
                t.n(desc = "Search", click = true, bounds = IntRect(160, 560, 320, 640)),
                t.n(desc = "Profile", click = true, bounds = IntRect(320, 560, 480, 640)),
            )),
        ))
        val m = Interpreter.interpret(snap(root))
        assertEquals(listOf("Home", "Search", "Profile"), m.options.map { it.label })
        assertTrue(m.options.first().selected)
        assertTrue((m.header + m.items + m.footer).none { it is ItemBlock && it.title == "Search" })
    }

    @Test fun `a page-sized clickable layout is looked through, not collapsed into one row`() {
        val t = Tree()
        val root = t.n(click = true, bounds = IntRect(0, 0, 480, 640), children = arrayOf(
            t.n("android.widget.Button", text = "One", click = true, bounds = IntRect(0, 0, 480, 100)),
            t.n("android.widget.Button", text = "Two", click = true, bounds = IntRect(0, 100, 480, 200)),
            t.n("android.widget.Button", text = "Three", click = true, bounds = IntRect(0, 200, 480, 300)),
        ))
        val m = Interpreter.interpret(snap(root))
        assertEquals(listOf("Two", "Three"), m.actions.map { it.label })
        assertEquals(listOf("One"), m.header.filterIsInstance<ButtonBlock>().map { it.label })
    }

    @Test fun `recipe picks title, actions and hides nodes`() {
        val t = Tree()
        val root = t.n(bounds = IntRect(0, 0, 480, 640), children = arrayOf(
            t.n(id = "android:id/alertTitle", text = "Delete file?"),
            t.n(id = "com.example:id/ad", text = "Buy now"),
            t.n(id = "android:id/message", text = "This can't be undone."),
            t.n("android.widget.Button", id = "android:id/button2", text = "Keep", click = true),
            t.n("android.widget.Button", id = "android:id/button1", text = "Delete", click = true),
        ))
        val recipe = Recipe(
            name = "dialog", packages = listOf("*"),
            whenPresent = listOf(Selector.parse("id:android:id/button1")),
            title = Selector.parse("id:android:id/alertTitle"),
            hide = listOf(Selector.parse("id:ad")),
            actions = listOf(RecipeAction(Selector.parse("id:android:id/button2"), tone = Tone.Muted), RecipeAction(Selector.parse("id:android:id/button1"))),
        )
        val s = snap(root)
        val chosen = RecipeBook(listOf(recipe)).forScreen(s)
        assertEquals("dialog", chosen?.name)
        val m = Interpreter.interpret(s, chosen)
        assertEquals("Delete file?", m.title)
        assertEquals(listOf("Keep" to Tone.Muted, "Delete" to Tone.Destructive), m.actions.map { it.label to it.tone })
        val texts = m.header.filterIsInstance<TextBlock>().map { it.text }
        assertEquals(listOf("This can't be undone."), texts)
        assertEquals("dialog", m.recipe)
    }

    @Test fun `app recipes beat global ones and unmatched screens get none`() {
        val any = Recipe("global", listOf("*"))
        val app = Recipe("app", listOf("com.example"))
        val other = Recipe("other", listOf("com.other"))
        val needs = Recipe("needs", listOf("com.example"), whenPresent = listOf(Selector.parse("id:missing")), priority = 99)
        val root = Tree().n()
        assertEquals("app", RecipeBook(listOf(any, app, other, needs)).forScreen(snap(root))?.name)
        assertNull(RecipeBook(listOf(other)).forScreen(snap(root)))
    }

    @Test fun `selectors`() {
        val node = UiNode(1, "android.widget.Button", "com.app:id/send", "Send now", "Send message")
        assertTrue(Selector.parse("id:send").matches(node))
        assertTrue(Selector.parse("id:com.app:id/send").matches(node))
        assertTrue(Selector.parse("text:send now").matches(node))
        assertTrue(Selector.parse("text~:NOW").matches(node))
        assertTrue(Selector.parse("desc~:message & class:Button").matches(node))
        assertTrue(!Selector.parse("id:sen").matches(node))
        assertTrue(!Selector.parse("class:TextView").matches(node))
    }

    @Test fun `tones from labels`() {
        assertEquals(Tone.Destructive, Interpreter.toneFor("Delete chat"))
        assertEquals(Tone.Muted, Interpreter.toneFor("Not now"))
        assertEquals(Tone.Secondary, Interpreter.toneFor("Send"))
        assertEquals(Tone.Primary, Interpreter.toneFor("Continue"))
    }

    @Test fun `identical rows get distinct keys`() {
        val t = Tree()
        val root = t.n(bounds = IntRect(0, 0, 480, 640), children = arrayOf(
            t.n("android.widget.ListView", scroll = true, bounds = IntRect(0, 0, 480, 640), children = arrayOf(
                t.n(click = true, children = arrayOf(t.n(text = "Same"))),
                t.n(click = true, children = arrayOf(t.n(text = "Same"))),
            )),
        ))
        val keys = Interpreter.interpret(snap(root)).items.map { it.key }
        assertEquals(2, keys.toSet().size)
    }
}

/** The Android 12 Settings home page on a 480x640 screen, as read on the Sidephone emulator. */
class SettingsHomeTest {
    @Test fun `large page title and a list that is mostly off screen`() {
        val t = Tree()
        val root = t.n("android.widget.FrameLayout", bounds = IntRect(144, 569, 448, 640), children = arrayOf(
            t.n("android.view.ViewGroup", id = "com.android.settings:id/settings_homepage_container", bounds = IntRect(144, 569, 448, 640), children = arrayOf(
                t.n("android.widget.TextView", id = "com.android.settings:id/homepage_title", text = "Settings", bounds = IntRect(48, 256, 312, 353)),
                t.n("android.view.ViewGroup", id = "com.android.settings:id/search_action_bar", click = true, bounds = IntRect(152, 426, 421, 480), children = arrayOf(
                    t.n("android.widget.TextView", text = "Search settings", bounds = IntRect(152, 426, 421, 480)),
                )),
                t.n("android.widget.ScrollView", id = "com.android.settings:id/main_content_scrollable_container", scroll = true, fwd = true, bounds = IntRect(144, 569, 448, 640), children = arrayOf(
                    t.n("android.widget.LinearLayout", bounds = IntRect(144, 569, 448, 640), children = arrayOf(
                        t.n("androidx.recyclerview.widget.RecyclerView", bounds = IntRect(144, 569, 448, 640), children = arrayOf(
                            t.n("android.widget.LinearLayout", click = true, bounds = IntRect(144, 569, 448, 640), children = arrayOf(
                                t.n("android.widget.TextView", id = "android:id/title", text = "Network & internet", bounds = IntRect(144, 569, 448, 640)),
                            )),
                        )),
                    )),
                )),
            )),
        ))
        val m = Interpreter.interpret(WindowSnapshot("com.android.settings", "com.android.settings.Settings", root, 480, 640))
        assertEquals("Settings", m.title)
        assertTrue(m.header.none { it is TextBlock })
        assertEquals(listOf("Search settings"), m.header.map { (it as ItemBlock).title })
        assertEquals(listOf("Network & internet"), m.items.map { (it as ItemBlock).title })
        assertTrue(m.list!!.canScrollForward)
    }
}

/** Settings scrolled down: containers report only the clipped sliver of their last row. */
class ClippedBoundsTest {
    @Test fun `a list with sliver bounds is still the list, not bottom tabs`() {
        val t = Tree()
        fun row(title: String, summary: String, top: Int) = t.n("android.widget.LinearLayout", click = true, bounds = IntRect(144, top + 54, 448, top + 120), children = arrayOf(
            t.n("android.widget.TextView", id = "android:id/title", text = title, bounds = IntRect(144, top, 300, top + 54)),
            t.n("android.widget.TextView", id = "android:id/summary", text = summary, bounds = IntRect(144, top + 54, 448, top + 120)),
        ))
        val root = t.n("android.widget.FrameLayout", bounds = IntRect(144, 612, 288, 640), children = arrayOf(
            t.n("android.widget.ScrollView", scroll = true, fwd = true, bounds = IntRect(144, 612, 288, 640), children = arrayOf(
                t.n("androidx.recyclerview.widget.RecyclerView", bounds = IntRect(144, 612, 288, 640), children = arrayOf(
                    row("Display", "Dark theme", 78), row("Wallpaper", "Home, lock screen", 267), row("Accessibility", "Display, interaction", 423),
                    t.n("android.widget.LinearLayout", click = true, bounds = IntRect(144, 612, 288, 640), children = arrayOf(t.n(text = "Security", bounds = IntRect(144, 612, 288, 640)))),
                )),
            )),
        ))
        val m = Interpreter.interpret(WindowSnapshot("com.android.settings", "Home", root, 480, 640))
        assertTrue(m.options.isEmpty())
        assertEquals(listOf("Display", "Wallpaper", "Accessibility", "Security"), m.items.map { (it as ItemBlock).title })
        assertTrue(m.list!!.canScrollForward)
    }
}
