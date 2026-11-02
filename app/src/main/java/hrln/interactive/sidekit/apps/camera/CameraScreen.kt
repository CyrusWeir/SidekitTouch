package hrln.interactive.sidekit.apps.camera

import androidx.compose.foundation.background
import hrln.interactive.sidekit.design.action.PublishChrome
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.cameraGraph(nav: NavHostController) {
    composable(SubApp.Camera.route) {
        val vm: CameraViewModel = viewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        CameraScreen(
            state = state,
            onToggleFlash = vm::toggleFlash,
            onPhotos = {},
            onShutter = vm::takePhoto,
            onToggleRecording = vm::toggleRecording,
        )
    }
}

/** Bottom bar in "Camera no flash" (71:1986) is 108 high; the recording bar (71:2114) is the standard 112. */

/** Flash icon sits at (392, 27) inside the viewfinder; its touch target is padded out around it. */
private val FlashIconOffsetX = 392.dp
private val FlashIconOffsetY = 27.dp
private val FlashTouchPadding = 16.dp

/** "Camera no flash" (71:1986) and "Camera Recording" (71:2114). No header. */
@Composable
fun CameraScreen(
    state: CameraUiState,
    onToggleFlash: () -> Unit,
    onPhotos: () -> Unit,
    onShutter: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val spacing = SidekitTheme.spacing
    Column(Modifier.fillMaxSize().padding(spacing.pagePadding)) {
        Viewfinder(state.flashOn, onToggleFlash, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(spacing.pageGap))
        // The shared action bar draws here. Pressing Record turns that button into the red timer
        // while the other two merge into it; stopping splits them back out.
        Spacer(Modifier.height(spacing.actionBarHeight))
    }
    PublishChrome(
        header = null,
        actions = if (state.recording) {
            listOf(SideAction("record", state.elapsedLabel, SidekitIcon.Videocam, tone = ActionTone.Destructive, contentDescription = "Stop recording", onClick = onToggleRecording))
        } else {
            listOf(
                SideAction("photos", icon = SidekitIcon.PhotoLibrary, tone = ActionTone.Muted, contentDescription = "Photos", onClick = onPhotos),
                SideAction("shutter", icon = SidekitIcon.PhotoCamera, contentDescription = "Take photo", onClick = onShutter),
                SideAction("record", icon = SidekitIcon.Videocam, contentDescription = "Record video", onClick = onToggleRecording),
            )
        },
    )
}

/** The mock's viewfinder is a photo; this is a token-coloured placeholder in the same slot. */
@Composable
private fun Viewfinder(flashOn: Boolean, onToggleFlash: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .clip(SidekitTheme.shapes.item)
            .background(SidekitTheme.colors.surfaceA2),
    ) {
        val iconSize = SidekitTheme.spacing.iconSize
        Box(
            Modifier
                .offset(FlashIconOffsetX - FlashTouchPadding, FlashIconOffsetY - FlashTouchPadding)
                .size(iconSize + FlashTouchPadding * 2)
                .clip(SidekitTheme.shapes.control)
                .clickable(
                    onClickLabel = if (flashOn) "Turn flash off" else "Turn flash on",
                    role = Role.Switch,
                    onClick = onToggleFlash,
                ),
            contentAlignment = Alignment.Center,
        ) {
            SidekitIconImage(
                icon = if (flashOn) SidekitIcon.FlashOn else SidekitIcon.FlashOff,
                contentDescription = if (flashOn) "Flash on" else "Flash off",
            )
        }
    }
}

@SidephonePreview
@Composable
private fun CameraPreview() = SidekitPreviewFrame { CameraScreen(CameraUiState(), {}, {}, {}, {}) }

@SidephonePreview
@Composable
private fun CameraRecordingPreview() = SidekitPreviewFrame {
    CameraScreen(CameraUiState(flashOn = true, recording = true, elapsedSeconds = 1), {}, {}, {}, {})
}
