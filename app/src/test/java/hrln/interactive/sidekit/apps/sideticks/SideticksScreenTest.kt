package hrln.interactive.sidekit.apps.sideticks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SideticksScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun cleanup() { scope.cancel() }
    @Test fun `finishing T9 typing keeps draft open so due time options remain reachable`() {
        val chrome = hrln.interactive.sidekit.design.action.ChromeState()
        val keypad = hrln.interactive.sidekit.design.input.Keypad.forTesting(scope, hrln.interactive.sidekit.design.input.T9Words.Empty)
        var confirmed = false
        var dueRequested = false
        compose.setContent {
            hrln.interactive.sidekit.design.theme.SidekitTheme {
                hrln.interactive.sidekit.design.canvas.SidekitCanvas(chrome = chrome, keypad = keypad) {
                    SideticksScreen(SideticksUiState(draft = "Milk"), {}, {}, {}, {}, {},
                        { confirmed = true }, onDue = { dueRequested = true })
                }
            }
        }
        compose.onNodeWithContentDescription("Done typing").performClick()
        compose.runOnIdle { assertFalse(confirmed); chrome.toggleOptions() }
        compose.onNodeWithText("Due time").performClick()
        compose.runOnIdle { assertTrue(dueRequested) }
    }
    @Test fun `draft creates persisted reminder and swipe undo restores it`() {
        var saved: List<Tick> = emptyList()
        val repo = TicksRepository(object : TickStore {
            override fun read() = saved
            override fun write(ticks: List<Tick>) { saved = ticks }
        }, scope, Dispatchers.Unconfined)
        val vm = SideticksViewModel(repo)
        compose.setContent { SidekitPreviewFrame {
            val state by vm.state.collectAsState()
            SideticksScreen(state, { vm.toggle(it.id) }, vm::setShowTicked, vm::clearTicked,
                vm::startAdding, vm::updateDraft, vm::confirmDraft,
                onDelete = { vm.delete(listOf(it)) }, onUndo = vm::undoDelete)
        } }
        compose.onNodeWithText("New Sidetick").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Buy carrots")
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals("Buy carrots", saved.single().title) }
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions)).performTouchInput { swipeLeft() }
        compose.onNode(SemanticsMatcher("Delete button") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == "Delete"
        }).performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Buy carrots").assertExists()
        compose.runOnIdle { assertEquals("Buy carrots", saved.single().title) }
    }
}
