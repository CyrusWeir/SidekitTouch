package hrln.interactive.sidekit.apps.camera

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.media.Access
import hrln.interactive.sidekit.apps.media.MediaPermissions
import hrln.interactive.sidekit.apps.media.PermissionRationale
import hrln.interactive.sidekit.apps.media.allowAction
import hrln.interactive.sidekit.apps.media.rememberPermissionState
import hrln.interactive.sidekit.apps.photos.AlbumKey
import hrln.interactive.sidekit.apps.photos.photosViewerRoute
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.PublishChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

@SuppressLint("MissingPermission")
fun NavGraphBuilder.cameraGraph(nav: NavHostController) {
    composable(SubApp.Camera.route) {
        val context = LocalContext.current.applicationContext
        val controller = remember { CameraController(context) }
        val state by controller.state.collectAsStateWithLifecycle()
        val cameraPermission = rememberPermissionState(MediaPermissions.camera)
        val microphone = rememberPermissionState(MediaPermissions.microphone)
        CameraScreen(
            state = state,
            access = cameraPermission.access,
            microphone = microphone.access,
            onAllowCamera = cameraPermission::request,
            onAllowMicrophone = microphone::request,
            onToggleFlash = controller::toggleFlash,
            onPhotos = {
                val capture = state.lastCapture
                if (capture != null) nav.navigate(photosViewerRoute(AlbumKey.Camera, capture.lastPathSegment?.toLongOrNull() ?: -1))
                else nav.navigate(SubApp.Photos.route)
            },
            onShutter = controller::takePhoto,
            onToggleRecording = { if (microphone.granted) controller.toggleRecording() else microphone.request() },
            onStart = controller::start,
            onSurface = controller::surfaceReady,
            onStop = controller::stop,
        )
    }
}

@Composable
fun CameraScreen(
    state: CameraUiState,
    onToggleFlash: () -> Unit,
    onPhotos: () -> Unit,
    onShutter: () -> Unit,
    onToggleRecording: () -> Unit,
    access: Access = Access.Granted,
    microphone: Access = Access.Ask,
    onAllowCamera: () -> Unit = {},
    onAllowMicrophone: () -> Unit = {},
    onStart: (TextureView) -> Unit = {},
    onSurface: (TextureView, Int, Int) -> Unit = { _, _, _ -> },
    onStop: () -> Unit = {},
) {
    if (access != Access.Granted) {
        SidekitScaffold(title = "Camera", actions = listOf(SideAction("allow", if (access == Access.Blocked) "Open settings" else "Allow", SidekitIcon.Check, onClick = onAllowCamera))) { padding ->
            PermissionRationale("Use the camera", "Camera needs camera access to show a preview and save photos and videos to this phone.", access, padding)
        }
        return
    }
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(SidekitTheme.spacing.pagePadding).focusRequester(focus).onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_5)) { onShutter(); true } else false
    }.focusable()) {
        Viewfinder(state.flashOn, onToggleFlash, onStart, onSurface, onStop, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(SidekitTheme.spacing.pageGap))
        Spacer(Modifier.height(SidekitTheme.spacing.actionBarHeight))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PublishChrome(header = null, actions = if (state.recording) {
        listOf(SideAction("record", state.elapsedLabel, SidekitIcon.Videocam, tone = ActionTone.Destructive, contentDescription = "Stop recording", onClick = onToggleRecording))
    } else {
        listOf(
            SideAction("photos", icon = SidekitIcon.PhotoLibrary, tone = ActionTone.Muted, contentDescription = "Photos", onClick = onPhotos),
            SideAction("shutter", icon = SidekitIcon.PhotoCamera, contentDescription = if (state.busy) "Saving photo" else "Take photo", onClick = onShutter),
            SideAction("record", icon = SidekitIcon.Videocam, contentDescription = "Record video", onClick = onToggleRecording),
        )
    }, options = state.error?.let { listOf(SideAction("error", it, SidekitIcon.Close, onClick = {})) }.orEmpty())
}

@Composable
private fun Viewfinder(flashOn: Boolean, onToggleFlash: () -> Unit, onStart: (TextureView) -> Unit, onSurface: (TextureView, Int, Int) -> Unit, onStop: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(SidekitTheme.shapes.item).background(SidekitTheme.colors.dark)) {
        AndroidView(factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = onSurface(this@apply, width, height)
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        }, modifier = Modifier.fillMaxSize(), update = { onStart(it) })
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onStop() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); onStop() }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp).clip(SidekitTheme.shapes.control).clickable(onClickLabel = if (flashOn) "Turn flash off" else "Turn flash on", role = Role.Switch, onClick = onToggleFlash), contentAlignment = Alignment.Center) {
            SidekitIconImage(if (flashOn) SidekitIcon.FlashOn else SidekitIcon.FlashOff, contentDescription = if (flashOn) "Flash on" else "Flash off")
        }
    }
}

@SidephonePreview
@Composable
private fun CameraPreview() = SidekitPreviewFrame { CameraScreen(CameraUiState(), {}, {}, {}, {}) }
