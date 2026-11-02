package hrln.interactive.sidekit.apps.vault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.GraphicsMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `locked vault hides notes and locking clears displayed private content`() {
        val store = object : VaultStore {
            override fun read() = listOf(PrivateNote("1", "Secret title"))
            override fun write(notes: List<PrivateNote>) {}
        }
        val vm = VaultViewModel(store, kotlinx.coroutines.Dispatchers.Main.immediate)
        compose.setContent { SidekitPreviewFrame { VaultScreen(vm) {} } }
        compose.onRoot().captureRoboImage("/tmp/sidekit-vault.png")
        compose.onNodeWithText("Secret title").assertDoesNotExist()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
        compose.runOnIdle { vm.authenticated() }
        compose.waitForIdle()
        org.junit.Assert.assertTrue(vm.state.value.toString(), vm.state.value.unlocked)
        compose.onNodeWithText("Secret title").assertIsDisplayed()
        compose.onNodeWithText("Lock").performClick()
        compose.onNodeWithText("Secret title").assertDoesNotExist()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
    }
}
