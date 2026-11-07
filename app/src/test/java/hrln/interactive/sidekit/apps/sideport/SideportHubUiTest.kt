package hrln.interactive.sidekit.apps.sideport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.apps.sideport.bridge.BridgeMessage
import hrln.interactive.sidekit.apps.sideport.bridge.Conversation
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A ported app's hub: the app header, its chats and media, and Remove/Uninstall in the body. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SideportHubUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private lateinit var chrome: ChromeState
    private val app = PortedApp("com.example.chat", "Example Chat")
    private val chat = Conversation(
        key = "com.example.chat|1",
        packageName = "com.example.chat",
        title = "Alice",
        messages = listOf(BridgeMessage("Alice", "Lunch tomorrow?", 1L)),
    )

    /** Shows the hub with [state], holding the confirmation state the screen would own. */
    private fun show(state: SideportHubUiState) {
        compose.setContent {
            var ui by remember { mutableStateOf(state) }
            SidekitPreviewFrame {
                chrome = LocalChrome.current!!
                SideportHubContent(
                    ui,
                    onBack = { calls += "back" },
                    onOpen = { calls += "open" },
                    onChat = { calls += "chat:$it" },
                    onPlayer = { calls += "player" },
                    onBridge = { calls += "bridge" },
                    onInfo = { calls += "info" },
                    onMode = { calls += "mode" },
                    onDismissMessage = { ui = ui.copy(message = null) },
                    onAsk = { ui = ui.copy(confirming = it) },
                    onCancel = { ui = ui.copy(confirming = null) },
                    onRemove = { calls += "remove" },
                    onUninstall = { calls += "uninstall" },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1000)
    }

    private fun installed() = SideportHubUiState(app, version = "2.24.6.78", chats = listOf(chat))

    @Test fun `hub shows the app, its version and its actions in the body`() {
        show(installed())
        compose.onRoot().captureRoboImage("screenshots/sideport_hub.png")
        compose.onNodeWithText("Version 2.24.6.78").assertExists()
        compose.onNodeWithText("com.example.chat").assertExists()
        compose.onNodeWithText("Open as").assertExists()
        compose.onNodeWithText("Remove from Sideport").assertExists()
        compose.onNodeWithText("Uninstall app").assertExists()
    }

    @Test fun `view mode, chats and media open from their rows`() {
        show(installed().copy(mediaTitle = "Now playing"))
        compose.onNodeWithText("Open as").performClick()
        // Scroll to the end so both rows sit clear of the action bar, which overlays the list.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Alice"))
        compose.onNodeWithText("Now playing").performClick()
        // The last row can sit under the action bar at rest; run its click without hit-testing.
        compose.onNodeWithText("Alice").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("mode", "player", "chat:com.example.chat|1"), calls)
    }

    @Test fun `remove asks for confirmation in the action bar and can be cancelled`() {
        show(installed())
        compose.onNodeWithText("Remove from Sideport").performClick()
        compose.onRoot().captureRoboImage("screenshots/sideport_hub_confirm.png")
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Remove").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open").assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `confirming remove takes the app out of Sideport`() {
        show(installed())
        compose.onNodeWithText("Remove from Sideport").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("remove"), calls)
    }

    @Test fun `confirming uninstall hands the app to Android`() {
        show(installed())
        compose.onNodeWithText("Uninstall app").performClick()
        compose.onNodeWithText("Uninstall").performClick()
        assertEquals(listOf("uninstall"), calls)
        // Coming back from the uninstaller, the confirmation is gone.
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test fun `Options keeps only the system app page`() {
        show(installed())
        compose.runOnIdle { chrome.toggleOptions() }
        compose.onNodeWithText("App info").performClick()
        assertEquals(listOf("info"), calls)
    }

    @Test fun `an app that is gone has no Options`() {
        show(SideportHubUiState(app, version = null))
        compose.runOnIdle { chrome.toggleOptions() }
        compose.onNodeWithText("App info").assertDoesNotExist()
    }

    @Test fun `an app that is no longer installed says so and offers only Remove`() {
        show(SideportHubUiState(app, version = null))
        compose.onNodeWithText("Not installed").assertExists()
        compose.onNodeWithText("Open as").assertDoesNotExist()
        compose.onNodeWithText("Uninstall app").assertDoesNotExist()
        compose.onNodeWithText("Remove from Sideport").assertExists()
        compose.onRoot().captureRoboImage("screenshots/sideport_hub_missing.png")
    }
}
