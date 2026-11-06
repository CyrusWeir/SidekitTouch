package hrln.interactive.sidekit.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.settings.visibilityId
import hrln.interactive.sidekit.navigation.SubApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Arrange-apps edit buffer: seeds from stored `HomePreferences`, keeps edits local, and only
 * persists on [HomeArranger.commit]. The dock is derived (top [HomePreferences.DOCK_MAX] apps), so
 * reordering into/out of the top four pins/unpins automatically; reordering happens via the
 * select-then-up/down selection mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HomeArrangerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val ported = listOf(PortedApp("test.matrix", "Matrix"))
    private val subsById = SubApp.entries.associateBy { it.visibilityId() }
    private val portedById = ported.associateBy { it.visibilityId() }
    private val known = subsById.keys + portedById.keys

    private val photos = "builtin:Photos"
    private val matrix = "ported:test.matrix"

    @Before
    fun cleanPrefs() {
        HomePreferences(context).reset()
    }

    @Test
    fun `seeds from stored order, dock derived, freshly-seen apps appended`() {
        val home = HomePreferences(context)
        home.move(photos, 2)                          // persisted order change
        val arranger = HomeArranger(home, known)

        // home.homeOrder is the raw stored list; the arranger appends the freshly-seen ported id.
        assertEquals(home.homeOrder + listOf(matrix), arranger.order)
        // The dock is derived: the first DOCK_MAX apps of the resolved order.
        assertEquals(arrayListOf("builtin:Sidemessage", "builtin:Sidenotes", "builtin:Sideticks", "builtin:Phonebook"), arranger.dock)
        assertEquals(home.dockFor(known), arranger.dock)
        // Unknown/unseen/missing ids are dropped from the buffer order.
        assertFalse(arranger.order.contains("builtin:Nope"))
    }

    @Test
    fun `move reorders within bounds and clamps at the ends`() {
        val home = HomePreferences(context)
        val arranger = HomeArranger(home, known)
        // Default index of Photos is 4.
        assertEquals(4, arranger.order.indexOf(photos))
        arranger.move(photos, 1)
        assertEquals(5, arranger.order.indexOf(photos))
        arranger.move(photos, -100)
        assertEquals(0, arranger.order.indexOf(photos))
        arranger.move(photos, 100)
        assertEquals(arranger.order.lastIndex, arranger.order.indexOf(photos))
        // No-op: moving by zero, or an id not in the order.
        val before = arranger.order.toList()
        arranger.move(photos, 0)
        arranger.move("builtin:Missing", 1)
        assertEquals(before, arranger.order)
    }

    @Test
    fun `dock is the top four and reordering into or out of it pins and unpins`() {
        val home = HomePreferences(context)
        val arranger = HomeArranger(home, known)
        val defaultDock = SubApp.dock.map { it.visibilityId() }
        assertEquals(defaultDock, arranger.dock)       // defaults == first four (Photos at 4 is out)
        assertFalse(arranger.isInDock(photos))
        assertFalse(arranger.isInDock(matrix))

        arranger.move(photos, -1)                      // photos 4 -> 3, enters the dock
        assertTrue(arranger.isInDock(photos))
        assertEquals(HomePreferences.DOCK_MAX, arranger.dock.size)
        assertEquals(photos, arranger.dock.last())

        arranger.move(photos, 1)                       // back out of the dock
        assertFalse(arranger.isInDock(photos))
        assertEquals(defaultDock, arranger.dock)

        // A ported app can be brought into the dock by moving it up too (it starts appended last).
        arranger.move(matrix, -100)
        assertTrue(arranger.isInDock(matrix))
    }

    @Test
    fun `selection mode selects on toggle and moveSelected reorders the selected app`() {
        val home = HomePreferences(context)
        val arranger = HomeArranger(home, known)
        assertNull(arranger.selected)
        assertFalse(arranger.isSelected(photos))

        arranger.select(photos)
        assertEquals(photos, arranger.selected)
        assertTrue(arranger.isSelected(photos))

        arranger.moveSelected(-1)                      // photos 4 -> 3
        assertEquals(3, arranger.order.indexOf(photos))
        assertTrue(arranger.isSelected(photos))        // selection follows the app

        arranger.moveSelected(0)                       // no-op keeps selection and order
        assertEquals(3, arranger.order.indexOf(photos))

        arranger.select(photos)                        // deselect
        assertNull(arranger.selected)
        val before = arranger.order.toList()
        arranger.moveSelected(1)                       // nothing selected -> no-op
        assertEquals(before, arranger.order)
    }

    @Test
    fun `reset keeps only the known apps, so filtered apps stay out`() {
        val home = HomePreferences(context)
        val visibleKnown = known - photos
        val arranger = HomeArranger(home, visibleKnown)
        assertFalse(arranger.order.contains(photos))

        arranger.reset()
        assertFalse(arranger.order.contains(photos))
        assertEquals(SubApp.entries.map { it.visibilityId() }.filter { it in visibleKnown } + listOf(matrix), arranger.order)
    }

    @Test
    fun `reset restores built-in defaults, dock and clears the selection`() {
        val home = HomePreferences(context)
        val arranger = HomeArranger(home, known)
        arranger.move(photos, 3)
        arranger.select(photos)

        arranger.reset()
        assertEquals(SubApp.entries.map { it.visibilityId() } + listOf(matrix), arranger.order)
        assertEquals(SubApp.dock.map { it.visibilityId() }, arranger.dock)
        assertNull(arranger.selected)
    }

    @Test
    fun `commit persists the order and the dock follows the top four`() {
        val home = HomePreferences(context)
        val arranger = HomeArranger(home, known)
        arranger.move(photos, -1)                      // bring Photos into the dock
        arranger.commit()

        val stored = HomePreferences(context)
        assertEquals(arranger.order, stored.homeOrder)
        // The dock is derived from the committed order, not a stored list.
        assertEquals(stored.homeOrder.take(HomePreferences.DOCK_MAX), stored.dockFor(known))
        assertTrue(stored.isInDock(photos))
        assertEquals(SubApp.dock.map { it.visibilityId() }.dropLast(1) + listOf(photos), arranger.dock)
    }
}