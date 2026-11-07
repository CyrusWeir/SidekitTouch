package hrln.interactive.sidekit.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import hrln.interactive.sidekit.apps.settings.visibilityId
import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HomePreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val allIds: List<String> = SubApp.entries.map { it.visibilityId() }

    // Robolectric reuses one application across test methods, so start each test from defaults.
    @Before
    fun clearPrefs() {
        HomePreferences(context).reset()
    }

    private fun fresh() = HomePreferences(context)

    @Test
    fun `defaults equal the current hardcoded home and derived dock`() {
        val home = fresh()
        assertEquals(SubApp.entries.map { it.visibilityId() }, home.homeOrder)
        // The dock is the first DOCK_MAX apps of the order — the default dock apps are first.
        assertEquals(SubApp.dock.map { it.visibilityId() }, home.dockFor(allIds))
        assertEquals(
            listOf("builtin:Sidemessage", "builtin:Sidenotes", "builtin:Sideticks", "builtin:Phonebook"),
            home.homeOrder.take(HomePreferences.DOCK_MAX),
        )
    }

    @Test
    fun `order round-trips through a new instance and the dock follows it`() {
        fresh().also { home ->
            home.move(allIds[0], 3)                 // Sidemessage +3
            home.move(allIds[1], 2)                 // Sidenotes  +2
        }
        val restored = fresh()
        // Start: [Sidemessage, Sidenotes, Sideticks, Phonebook, ...]
        // after Sidemessage+3 -> [Sidenotes, Sideticks, Phonebook, Sidemessage, ...]
        // after Sidenotes+2   -> [Sideticks, Phonebook, Sidenotes, Sidemessage, ...]
        assertEquals(listOf("builtin:Sideticks", "builtin:Phonebook", "builtin:Sidenotes", "builtin:Sidemessage"), restored.homeOrder.take(4))
        assertEquals(restored.homeOrder.take(4), restored.dockFor(allIds))
        assertTrue(restored.isInDock("builtin:Sideticks"))
        assertFalse(restored.isInDock("builtin:Photos"))
    }

    @Test
    fun `move keeps a stable order and clamps at the ends`() {
        val home = fresh()
        home.move(allIds.first(), -10)        // already at top
        assertEquals(allIds.first(), home.homeOrder.first())
        home.move(allIds.first(), 200)        // past the end -> last
        assertEquals(allIds.first(), home.homeOrder.last())
        home.move("builtin:DoesNotExist", 1)  // unknown id is ignored
        assertEquals(allIds.first(), home.homeOrder.last())
    }

    @Test
    fun `reordering an app into the top four pins it in the dock`() {
        val home = fresh()
        // Photos starts at index 4 (outside the dock).
        assertFalse(home.isInDock("builtin:Photos"))
        val defaultDock = home.dockFor(allIds)
        assertEquals(SubApp.dock.map { it.visibilityId() }, defaultDock)

        home.move("builtin:Photos", -1)       // Photos 4 -> 3, trading places with Phonebook
        assertTrue(home.isInDock("builtin:Photos"))
        assertEquals(HomePreferences.DOCK_MAX, home.dockFor(allIds).size)
        assertEquals("builtin:Photos", home.homeOrder.take(4).last())   // Photos in, Phonebook moved out
        assertFalse(home.isInDock("builtin:Phonebook"))

        home.move("builtin:Photos", 1)        // back out of the dock
        assertFalse(home.isInDock("builtin:Photos"))
        assertEquals(defaultDock, home.dockFor(allIds))
    }

    @Test
    fun `unknown ids are skipped at render but still stored`() {
        val home = fresh()
        // Home still carries every default id (the missing app stays in storage) ...
        assertTrue(home.homeOrder.contains("builtin:Sideticks"))
        // ... but a known set without it renders without pulling it back in.
        val known = allIds.filterNot { it == "builtin:Sideticks" }
        assertEquals(known, home.homeOrderFor(known))

        // Dock is derived from the rendered order: [SM, SN, Phonebook, Photos] after Sideticks drops.
        assertEquals(listOf("builtin:Sidemessage", "builtin:Sidenotes", "builtin:Phonebook", "builtin:Photos"), home.dockFor(known))
    }

    @Test
    fun `newly seen ids are appended after the stored order`() {
        val home = fresh()
        val seen = allIds + "ported:com.example.New"
        val order = home.homeOrderFor(seen)
        assertEquals(allIds, order.take(allIds.size))
        assertEquals("ported:com.example.New", order.last())
    }

    @Test
    fun `dock renders in home order`() {
        val home = fresh()
        home.move("builtin:Phonebook", -2)   // [Sidemessage, Phonebook, Sidenotes, Sideticks, ...]
        home.move("builtin:Sidenotes", 1)    // [Sidemessage, Phonebook, Sideticks, Sidenotes, ...]
        // The dock is exactly the first four of the rendered order — no separate sort needed.
        assertEquals(home.homeOrderFor(allIds).take(HomePreferences.DOCK_MAX), home.dockFor(allIds))
    }

    @Test
    fun `reset restores defaults`() {
        fresh().also { home ->
            home.move(allIds.first(), 1)
            home.reset()
        }
        assertEquals(SubApp.entries.map { it.visibilityId() }, fresh().homeOrder)
        assertEquals(SubApp.dock.map { it.visibilityId() }, fresh().dockFor(allIds))
    }

    @Test
    fun `observer fires on order changes until unregistered`() {
        val home = fresh()
        var calls = 0
        val unregister = home.observe { calls++ }

        home.move(allIds.first(), 1)          // order change
        home.move(allIds.first(), 1)
        assertEquals(2, calls)

        unregister()
        home.move(allIds.first(), 1)
        assertEquals(2, calls)
    }
}