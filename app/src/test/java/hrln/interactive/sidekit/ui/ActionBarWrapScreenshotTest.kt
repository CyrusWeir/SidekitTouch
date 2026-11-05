package hrln.interactive.sidekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitScaffold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActionBarWrapScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun longLabelsWrap() = shoot("action_labels_wrap") {
        SidekitScaffold(
            title = "Wrap test",
            actions = listOf(
                SideAction.forward("a", "Open notification settings", onClick = {}),
                SideAction("b", "Mark as unread") {},
            ),
        ) {}
    }

    @Test fun denseLabelsWrap() = shoot("action_labels_wrap_dense") {
        SidekitScaffold(
            title = "Dense wrap test",
            actions = listOf(
                SideAction("a", "Inbox cache refresh failed, retry later", dense = true) {},
                SideAction("b", "Dismiss", dense = true) {},
            ),
        ) {}
    }
}
