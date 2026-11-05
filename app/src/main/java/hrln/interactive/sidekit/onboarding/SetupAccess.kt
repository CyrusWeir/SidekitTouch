package hrln.interactive.sidekit.onboarding

import android.Manifest.permission.*
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

internal data class SetupAccess(
    val title: String,
    val reason: String,
    val permissions: List<String> = emptyList(),
    val enabled: (Context) -> Boolean,
    val intent: ((Context) -> Intent)? = null,
)

internal fun roleAccess(role: String, title: String, reason: String) = SetupAccess(title, reason,
    enabled = { it.getSystemService(RoleManager::class.java).isRoleHeld(role) },
    intent = { context ->
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(role)) roles.createRequestRoleIntent(role)
        else Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    })

internal val homeAccess = roleAccess(RoleManager.ROLE_HOME, "Set as home app", "Press Home to return to Sidekit.")

internal fun setupAccess(): List<SetupAccess> {
    fun runtime(title: String, reason: String, vararg permissions: String) = SetupAccess(title, reason, permissions.toList(),
        enabled = { c -> permissions.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } })
    fun special(title: String, reason: String, action: String, packageUri: Boolean = false, check: (Context) -> Boolean) =
        SetupAccess(title, reason, enabled = check, intent = { c -> Intent(action).apply { if (packageUri) data = Uri.parse("package:${c.packageName}") } })
    fun accessibility(suffix: String): (Context) -> Boolean = { c ->
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull(ComponentName::unflattenFromString)
            .any { it.packageName == c.packageName && it.className.endsWith(suffix) }
    }
    return buildList {
        add(special("Keypad Back key", "Needed for the top-left Back key. Enable Sidekit keypad in Accessibility. Holding Back still opens the dialer.", Settings.ACTION_ACCESSIBILITY_SETTINGS, check = accessibility("KeypadAccessibilityService")))
        add(roleAccess(RoleManager.ROLE_SMS, "Default SMS app", "Needed to receive and manage texts in Sidemessage. Choose this before granting SMS access."))
        add(runtime("Text messages", "Read, send and receive texts in Sidemessage.", READ_SMS, SEND_SMS, RECEIVE_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH))
        add(runtime("Contacts & calls", "Show contact names and recent calls, and place calls from Phonebook.", READ_CONTACTS, READ_CALL_LOG, CALL_PHONE))
        if (Build.VERSION.SDK_INT >= 33) add(runtime("Notifications", "Show message, timer and reminder alerts.", POST_NOTIFICATIONS))
        add(special("Alarms & reminders", "Allow Clock alarms and Sideticks reminders at the time you choose.", Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) { it.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() })
        if (Build.VERSION.SDK_INT >= 34) add(special("Full-screen alarms", "Let a ringing alarm appear over the lock screen.", Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true) { it.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() })
        add(runtime("Camera", "Take photos with Camera.", CAMERA))
        add(runtime("Microphone", "Speak to Translate and record audio where supported.", RECORD_AUDIO))
        if (Build.VERSION.SDK_INT >= 33) {
            add(runtime("Photos & video", "Browse your shared photos and videos. Limited access can still be managed in Android settings.", READ_MEDIA_IMAGES, READ_MEDIA_VIDEO))
            add(runtime("Music", "Browse audio stored on your phone.", READ_MEDIA_AUDIO))
        } else add(runtime("Media & files", "Browse shared photos, videos and music.", READ_EXTERNAL_STORAGE))
        add(runtime("Calendar", "Read and create calendar events.", READ_CALENDAR, WRITE_CALENDAR))
        add(runtime("Approximate location", "Find local weather without precise location.", ACCESS_COARSE_LOCATION))
        add(runtime("Activity", "Count steps in Health.", ACTIVITY_RECOGNITION))
        add(runtime("Nearby devices", "Show connected Bluetooth devices.", BLUETOOTH_CONNECT))
        add(special("System controls", "Allow brightness and other supported system controls.", Settings.ACTION_MANAGE_WRITE_SETTINGS, true) { Settings.System.canWrite(it) })
        add(special("Do Not Disturb", "Allow supported sound controls to change interruption settings.", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted })
        add(special("Sideport accessibility", "Optional: lets Sideport read and control the screen of apps you project into Sidekit. Enable only if you want Sideport.", Settings.ACTION_ACCESSIBILITY_SETTINGS, check = accessibility("SideportService")))
        add(special("Sideport notifications", "Optional: lets Sideport read notifications and reply to supported chats.", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) })
        add(special("Install Sideport apps", "Optional: allow installing APKs you choose. Android asks before each install.", Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true) { it.packageManager.canRequestPackageInstalls() })
    }
}
