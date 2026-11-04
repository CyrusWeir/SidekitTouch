package hrln.interactive.sidekit.apps.sideport.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import hrln.interactive.sidekit.apps.sideport.recipes.RecipeJson
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sideport's projection service: reads ported apps' screens and draws them as Sidekit screens.
 *
 * Idle (no ported app open) it asks for no events and filters no keys, so it costs nothing next
 * to the keypad service. A session ([ProjectionController]) turns events and key filtering on for
 * as long as the ported app, or something it opened, is in front.
 */
class SideportService : AccessibilityService() {
    private var controller: ProjectionController? = null

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
        idle()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        controller?.end()
        controller = null
        if (instance === this) instance = null
        _connected.value = false
    }

    /** Starts projecting [app]; the caller launches the app itself right after. */
    fun startSession(app: PortedApp) {
        controller?.end()
        // Only the default home counts as "back home". Other apps can declare HOME activities too
        // (AOSP Settings has FallbackHome), and those are apps the user may well port.
        val homes = setOfNotNull(
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.takeIf { it != "android" },
        ) - app.packageName
        val session = ProjectionController(this, app, RecipeJson.load(this), homes) { ended ->
            if (controller === ended) {
                controller = null
                _session.value = null
                idle()
            }
        }
        controller = session
        _session.value = app.packageName
        listen()
        session.start()
    }

    fun endSession() {
        controller?.end()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        controller?.onEvent(event)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = controller?.onKey(event, fromFilter = true) ?: false

    override fun onInterrupt() = Unit

    private fun listen() = configure(
        events = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        filterKeys = true,
    )

    private fun idle() = configure(events = 0, filterKeys = false)

    private fun configure(events: Int, filterKeys: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = events
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
        var flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        if (filterKeys) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = flags
        serviceInfo = info
    }

    companion object {
        private const val NOTIFICATION_TIMEOUT_MS = 80L

        @Volatile var instance: SideportService? = null
            private set

        private val _connected = MutableStateFlow(false)
        /** Whether the user has enabled the service (Settings > Accessibility > Sideport). */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _session = MutableStateFlow<String?>(null)
        /** Package of the ported app being projected, if any. */
        val session: StateFlow<String?> = _session.asStateFlow()

        /**
         * The keypad's Back key while a projection is showing. The keypad service calls this first:
         * a global Back would skip the projection's own layers (options bar, inspector).
         */
        fun handleSystemBack(): Boolean {
            val c = instance?.controller ?: return false
            if (c.state.app.mode != hrln.interactive.sidekit.apps.sideport.registry.PortMode.Projected) return false
            c.back()
            return true
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, SideportService::class.java)
            return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == me }
        }
    }
}
