package hrln.interactive.sidekit.apps.sideport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.apps.sideport.interpret.Tone
import hrln.interactive.sidekit.apps.sideport.recipes.RecipeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class RecipeJsonTest {
    @Test fun `parses a recipe`() {
        val recipe = RecipeJson.parse(
            """
            {
              "name": "Chat",
              "package": "com.chat",
              "screen": "ConversationActivity",
              "whenPresent": ["id:entry"],
              "title": "id:conversation_title",
              "hide": ["id:banner"],
              "itemTitle": "id:message_text",
              "actions": ["id:attach", { "select": "id:send", "label": "Send", "tone": "secondary" }],
              "options": [{ "select": "desc:Video call" }],
              "priority": 3
            }
            """,
        ).single()
        assertEquals("Chat", recipe.name)
        assertEquals(listOf("com.chat"), recipe.packages)
        assertEquals("ConversationActivity", recipe.screen)
        assertEquals(2, recipe.actions.size)
        assertEquals(Tone.Secondary, recipe.actions[1].tone)
        assertEquals("Send", recipe.actions[1].label)
        assertEquals(3, recipe.priority)
    }

    @Test fun `bundled recipes all load`() {
        val book = RecipeJson.load(ApplicationProvider.getApplicationContext<Context>())
        val names = book.recipes.map { it.name }
        assertTrue(names.containsAll(listOf("Android dialog", "Permission request", "Android settings", "Preference screen")))
    }
}
