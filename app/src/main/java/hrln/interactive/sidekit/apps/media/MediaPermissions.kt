package hrln.interactive.sidekit.apps.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * Runtime permissions the media apps ask for, in context (when the user first opens the feature
 * that needs them). Below API 33 one storage permission covers images, video and audio; from 33 on
 * each kind has its own.
 */
object MediaPermissions {
    val images: Array<String> = storage(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    val video: Array<String> = storage(Manifest.permission.READ_MEDIA_VIDEO)
    val audio: Array<String> = storage(Manifest.permission.READ_MEDIA_AUDIO)
    val camera: Array<String> = arrayOf(Manifest.permission.CAMERA)
    val microphone: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun storage(vararg tiramisu: String): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(*tiramisu) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun granted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/** Where a permission stands, as far as a screen needs to know. */
enum class Access {
    Granted,

    /** Not asked yet, or asked and refused once: "Allow" shows the system prompt. */
    Ask,

    /** Refused for good: only the app's system settings page can grant it now. */
    Blocked,
}

@Stable
class PermissionState internal constructor(
    private val permissions: Array<String>,
    private val context: Context,
) {
    var access: Access by mutableStateOf(if (MediaPermissions.granted(context, permissions)) Access.Granted else Access.Ask)
        internal set

    internal var launch: (() -> Unit)? = null

    val granted: Boolean get() = access == Access.Granted

    /** Shows the system prompt, or the app's settings page if the user has blocked the prompt. */
    fun request() {
        if (access == Access.Blocked) openAppSettings(context) else launch?.invoke()
    }

    internal fun refresh() {
        if (MediaPermissions.granted(context, permissions)) access = Access.Granted
        else if (access == Access.Granted) access = Access.Ask
    }

    internal fun onResult(context: Context) {
        access = when {
            MediaPermissions.granted(context, permissions) -> Access.Granted
            // Refused and no rationale to show: the system won't prompt again.
            permissions.none { context.findActivity()?.shouldShowRequestPermissionRationale(it) == true } -> Access.Blocked
            else -> Access.Ask
        }
    }
}

/**
 * Tracks [permissions] for a screen. Re-checked whenever the screen starts, so a grant made in
 * system settings is picked up on return. In previews everything counts as granted.
 */
@Composable
fun rememberPermissionState(permissions: Array<String>, onGranted: () -> Unit = {}): PermissionState {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val state = remember(permissions.joinToString()) { PermissionState(permissions, context) }
    if (inspection) {
        state.access = Access.Granted
        return state
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.onResult(context)
        if (state.granted) onGranted()
    }
    state.launch = { launcher.launch(permissions) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) state.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

/** The bar's answer to a missing permission: one wide "Allow" (or "Open settings" once blocked). */
fun allowAction(state: PermissionState, what: String): SideAction =
    if (state.access == Access.Blocked) {
        SideAction.forward("allow", "Open settings", state::request, icon = SidekitIcon.Settings)
    } else {
        SideAction("allow", "Allow", SidekitIcon.Check, iconAtEnd = true, tone = ActionTone.Secondary, contentDescription = "Allow access to $what", onClick = state::request)
    }

/**
 * The body row explaining why a permission is needed, shown in place of the content it unlocks.
 */
@Composable
fun PermissionRationale(title: String, reason: String, access: Access, padding: PaddingValues, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap)) {
        SidekitCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)) {
                SidekitText(title, style = SidekitTheme.typography.headline, maxLines = 2)
                SidekitText(
                    if (access == Access.Blocked) "$reason\n\nAccess was turned off. Allow it in Settings, then come back." else reason,
                    style = SidekitTheme.typography.label,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 8,
                )
            }
        }
    }
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
