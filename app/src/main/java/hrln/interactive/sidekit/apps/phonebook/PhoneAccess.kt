package hrln.interactive.sidekit.apps.phonebook

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.Locale

/**
 * A runtime permission (or group) as screen state. [granted] is re-checked every time the screen
 * resumes, so a grant made in system Settings shows up on return. Once the user has refused for
 * good (the system will no longer ask), [request] opens this app's Settings page instead.
 */
@Stable
class PermissionGate internal constructor(initial: Boolean) {
    var granted by mutableStateOf(initial)
        internal set

    /** True once the system won't show its dialog any more; "Allow" then means Settings. */
    var deniedForGood by mutableStateOf(false)
        internal set

    internal var launch: () -> Unit = {}

    fun request() = launch()
}

fun Context.hasPermissions(vararg permissions: String) =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun rememberPermissionGate(vararg permissions: String, onResult: (Boolean) -> Unit = {}): PermissionGate {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val perms = remember { arrayOf(*permissions) }
    val gate = remember { PermissionGate(context.hasPermissions(*perms)) }
    val latest by rememberUpdatedState(onResult)
    LifecycleResumeEffect(gate) {
        gate.granted = context.hasPermissions(*perms)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = context.hasPermissions(*perms)
        gate.granted = ok
        if (!ok && activity != null) {
            gate.deniedForGood = perms.filterNot { context.hasPermissions(it) }.none { activity.shouldShowRequestPermissionRationale(it) }
        }
        latest(ok)
    }
    gate.launch = {
        if (gate.deniedForGood) openAppSettings(context) else launcher.launch(perms)
    }
    return gate
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

fun placeCall(context: Context, number: String) {
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return openDialer(context, number)
    try {
        telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
    } catch (_: SecurityException) {
        openDialer(context, number)
    }
}

/** The system dialer with [number] filled in. Needs no permission. */
fun openDialer(context: Context, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Formats a number for display in the phone's region ("0412345678" -> "0412 345 678"). */
object PhoneFormat {
    @Volatile private var region: String? = null

    fun region(context: Context): String = region ?: run {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country
        iso.uppercase(Locale.ROOT).also { region = it }
    }

    fun format(number: String, region: String): String =
        if (number.isBlank()) number else PhoneNumberUtils.formatNumber(number, region) ?: number
}

/**
 * Keypad digits for screens that take a number, not text (the contacts search and the dial pad).
 * `0`-`9`, `*`, `#` and Delete are delivered on key down, and holding `0` asks for a "+".
 *
 * By default the element takes focus itself so presses reach it: right for a screen that is only
 * a number. A screen with rows to move between passes its scaffold's [parking] spot instead and
 * puts this on the scaffold: presses then reach it from the parking spot or from any row, and
 * the D-pad still walks the rows (a focusable list would hold focus as one invisible block).
 */
@Composable
fun Modifier.keypadDigits(
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onLongZero: (() -> Unit)? = null,
    enabled: Boolean = true,
    parking: FocusRequester? = null,
): Modifier {
    if (!enabled) return this
    val focus = parking ?: remember { FocusRequester() }
    val key by rememberUpdatedState(onKey)
    val delete by rememberUpdatedState(onDelete)
    val longZero by rememberUpdatedState(onLongZero)
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    val digits = onPreviewKeyEvent { e ->
        val native = e.nativeKeyEvent
        val c = when (native.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (native.keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_STAR -> '*'
            KeyEvent.KEYCODE_POUND -> '#'
            KeyEvent.KEYCODE_DEL -> '\b'
            else -> return@onPreviewKeyEvent false
        }
        if (native.action == KeyEvent.ACTION_DOWN) {
            when {
                native.repeatCount == 0 -> if (c == '\b') delete() else key(c)
                native.repeatCount == 1 && c == '0' -> longZero?.invoke()
                native.repeatCount > 0 && c == '\b' -> delete()
            }
        }
        true
    }
    return if (parking != null) digits else focusRequester(focus).then(digits).focusable()
}
