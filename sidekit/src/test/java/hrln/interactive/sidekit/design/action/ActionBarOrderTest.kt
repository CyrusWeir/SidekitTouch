package hrln.interactive.sidekit.design.action

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionBarOrderTest {
    @Test
    fun `existing slots are moved into the target order`() {
        val slots = mutableListOf(
            slot("home"),
            slot("about"),
            slot("settings"),
            slot("surplus"),
        )

        alignSlotsToTarget(
            slots,
            listOf(action("home"), action("settings"), action("about")),
        )

        assertEquals(listOf("home", "settings", "about", "surplus"), slots.map { it.key })
    }

    private fun slot(key: String) = ActionSlot(action(key), present = true)
    private fun action(key: String) = SideAction(key, label = key, onClick = {})
}
