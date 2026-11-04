package hrln.interactive.sidekit.apps.sideport

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.sideport.interpret.ActionSpec
import hrln.interactive.sidekit.apps.sideport.interpret.Block
import hrln.interactive.sidekit.apps.sideport.interpret.FieldBlock
import hrln.interactive.sidekit.apps.sideport.interpret.ItemBlock
import hrln.interactive.sidekit.apps.sideport.interpret.ListRegion
import hrln.interactive.sidekit.apps.sideport.interpret.ScreenModel
import hrln.interactive.sidekit.apps.sideport.interpret.TextBlock
import hrln.interactive.sidekit.apps.sideport.interpret.Tone
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.service.ProjectionActions
import hrln.interactive.sidekit.apps.sideport.service.ProjectionState
import hrln.interactive.sidekit.apps.sideport.ui.ProjectedScreen
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectionUiTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : ProjectionActions {
        val calls = mutableListOf<String>()
        override fun press(block: Block, long: Boolean) { calls += "press:${block.key}" }
        override fun pressAction(handle: Int, key: String) { calls += "action:$key" }
        override fun setText(fieldKey: String, text: String) { calls += "text:$text" }
        override fun loadMore(forward: Boolean) { calls += "more:$forward" }
        override fun back() { calls += "back" }
        override fun exitToSidekit() { calls += "exit" }
        override fun showOriginal() { calls += "original" }
        override fun toggleInspector() { calls += "inspect" }
        override fun exportTree() { calls += "export" }
        override fun openHub() { calls += "hub" }
    }

    @Test fun `projected screen draws the app as Sidekit rows and presses through`() {
        val state = ProjectionState(PortedApp("com.chat", "Chat"))
        state.model = ScreenModel(
            packageName = "com.chat",
            title = "Chats",
            header = listOf(FieldBlock("field:search", 1, "", "Search chats")),
            items = emptyList(),
            footer = emptyList(),
            list = ListRegion(2, canScrollForward = false, canScrollBackward = false),
            actions = listOf(ActionSpec("fab", "New chat", 9, Tone.Primary)),
            options = listOf(ActionSpec("tab.Calls", "Calls", 10, Tone.Muted)),
            signature = "com.chat|Main|Chats",
        )
        state.items = listOf(
            ItemBlock("item:alice", "Alice", "Lunch tomorrow?", click = 3, inList = true),
            ItemBlock("item:bob", "Bob", "Sent a photo", click = 4, inList = true),
            TextBlock("row:note", "Messages are end-to-end encrypted", inList = true),
        )
        val actions = Recorder()
        compose.setContent { SidekitPreviewFrame { ProjectedScreen(state, actions) } }
        compose.onRoot().captureRoboImage("/tmp/sidekit-sideport-projection.png")
        compose.onNodeWithText("Chats").assertExists()
        compose.onNodeWithText("Alice").performClick()
        compose.onNodeWithText("New chat").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("press:item:alice", "action:fab", "back"), actions.calls)
    }

    @Test fun `opening state before the first read`() {
        val state = ProjectionState(PortedApp("com.chat", "Chat"))
        compose.setContent { SidekitPreviewFrame { ProjectedScreen(state, Recorder()) } }
        compose.onNodeWithText("Opening Chat…").assertExists()
    }
}
