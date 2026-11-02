package hrln.interactive.sidekit.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitSliderItem
import hrln.interactive.sidekit.design.components.SidekitToggleTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

private val QuickToggle.icon: SidekitIcon
    get() = when (this) {
        QuickToggle.Airplane -> SidekitIcon.Travel
        QuickToggle.Wifi -> SidekitIcon.AndroidWifi3Bar
        QuickToggle.Bedtime -> SidekitIcon.Bedtime
        QuickToggle.Silent -> SidekitIcon.NotificationsOff
        QuickToggle.MobileData -> SidekitIcon.AndroidCell4Bar
        QuickToggle.Bluetooth -> SidekitIcon.Bluetooth
        QuickToggle.Flashlight -> SidekitIcon.FlashlightOn
        QuickToggle.QrScanner -> SidekitIcon.QrCodeScanner
    }

/**
 * Launcher Control centre (53:642): two rows of toggles plus volume and brightness,
 * anchored to the bottom over a dimmed launcher. Tap the dimmed area to close.
 */
@Composable
fun ControlCentre(
    state: LauncherUiState,
    onDismiss: () -> Unit,
    onToggle: (QuickToggle, Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(context) { SystemControls(context, onToggle, onVolumeChange, onBrightnessChange) }
    var explanation by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var grant by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> explanation = if (granted) "Allowed. Tap the control again." else "Permission denied. You can allow it in app settings."; grant = null; controller.refresh() }
    controller.explain = { message, action -> explanation = message; grant = action }
    controller.permission = { name -> permission.launch(name) }
    androidx.lifecycle.compose.LifecycleStartEffect(controller) {
        controller.start()
        onStopOrDispose { controller.stop() }
    }
    if (explanation != null) {
        hrln.interactive.sidekit.design.action.PublishActionOverride(listOfNotNull(
            hrln.interactive.sidekit.design.action.SideAction("close", "Done") { explanation = null; grant = null },
            grant?.let { action -> hrln.interactive.sidekit.design.action.SideAction("allow", "Allow", onClick = action) },
        ))
    } else {
        hrln.interactive.sidekit.design.action.PublishActionOverride(emptyList(), listOf(
            hrln.interactive.sidekit.design.action.SideAction("media", "Media") { controller.selectStream(android.media.AudioManager.STREAM_MUSIC) },
            hrln.interactive.sidekit.design.action.SideAction("ring", "Ring") { controller.selectStream(android.media.AudioManager.STREAM_RING) },
            hrln.interactive.sidekit.design.action.SideAction("alarm", "Alarm") { controller.selectStream(android.media.AudioManager.STREAM_ALARM) },
        ))
    }
    val spacing = SidekitTheme.spacing
    Box(
        modifier
            .fillMaxSize()
            .testTag(LauncherTags.CONTROL_CENTRE)
            .background(SidekitTheme.colors.surfaceA1.copy(alpha = 0.8f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close control centre", onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(spacing.pagePadding)
                // Swallow taps on the panel so they don't dismiss it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(spacing.itemOuterGap),
        ) {
            explanation?.let {
                hrln.interactive.sidekit.design.components.SidekitCard(Modifier.padding(bottom = spacing.actionBarHeight + spacing.pageGap)) {
                    hrln.interactive.sidekit.design.components.SidekitText(it, style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                }
            }
            if (explanation == null) {
                QuickToggle.entries.chunked(4).forEach { row ->
                    SidekitItemRow {
                        row.forEach { toggle ->
                            SidekitToggleTile(
                                icon = toggle.icon,
                                label = controller.label(toggle),
                                checked = state.toggles[toggle] == true,
                                onCheckedChange = { controller.toggle(toggle, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                SidekitSliderItem(SidekitIcon.BrandAwareness, controller.volumeLabel, state.volume, controller::volume)
                SidekitSliderItem(SidekitIcon.LightMode, "Brightness", state.brightness, controller::brightness)
            }
        }
    }
}

/** Registered only for the visible panel; setters always re-read the platform's accepted value. */
internal class SystemControls(
    private val context: android.content.Context,
    private val toggleState: (QuickToggle, Boolean) -> Unit,
    private val volumeState: (Float) -> Unit,
    private val brightnessState: (Float) -> Unit,
) {
    private val resolver = context.contentResolver
    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private val notifications = context.getSystemService(android.app.NotificationManager::class.java)
    private val camera = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
    private val bluetooth get() = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val activity get() = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    private var stream by androidx.compose.runtime.mutableIntStateOf(android.media.AudioManager.STREAM_MUSIC)
    val volumeLabel get() = when (stream) {
        android.media.AudioManager.STREAM_RING -> "Ring volume"
        android.media.AudioManager.STREAM_ALARM -> "Alarm volume"
        else -> "Volume"
    }
    fun selectStream(value: Int) { stream = value; refresh() }
    fun label(toggle: QuickToggle) = if (toggle == QuickToggle.Bluetooth && !has(android.Manifest.permission.BLUETOOTH_CONNECT)) "Bluetooth ?" else toggle.label
    private var torchId: String? = null
    private var active = false
    private var pendingBrightness: Float? = null
    private var oldBrightness = -1f
    var explain: (String, (() -> Unit)?) -> Unit = { _, _ -> }
    var permission: (String) -> Unit = {}
    private val commitBrightness = Runnable {
        pendingBrightness?.let { value ->
            attempt {
                if (android.provider.Settings.System.canWrite(context)) {
                    android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, (value * 255).toInt().coerceIn(1, 255))
                }
            }
        }
        pendingBrightness = null
        restoreWindow()
        refresh()
    }
    private val observer = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) = refresh()
    }
    private val torch = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == torchId) toggleState(QuickToggle.Flashlight, enabled) }
        override fun onTorchModeUnavailable(id: String) { if (id == torchId) toggleState(QuickToggle.Flashlight, false) }
    }
    fun start() {
        if (active) return
        active = true
        oldBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        attempt {
            torchId = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            camera.registerTorchCallback(torch, handler)
        }
        resolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
        resolver.registerContentObserver(android.provider.Settings.Global.CONTENT_URI, true, observer)
        val filter = android.content.IntentFilter().apply {
            addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(commitBrightness)
        commitBrightness.run()
        resolver.unregisterContentObserver(observer)
        context.unregisterReceiver(receiver)
        camera.unregisterTorchCallback(torch)
        restoreWindow()
    }
    fun refresh() {
        toggleState(QuickToggle.Airplane, android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0)
        toggleState(QuickToggle.Wifi, context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled)
        toggleState(QuickToggle.MobileData, runCatching { context.getSystemService(android.telephony.TelephonyManager::class.java).isDataEnabled }.getOrDefault(false))
        toggleState(QuickToggle.Silent, audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL)
        toggleState(QuickToggle.Bedtime, notifications.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        toggleState(QuickToggle.Bluetooth, has(android.Manifest.permission.BLUETOOTH_CONNECT) && bluetooth?.isEnabled == true)
        volumeState(audio.getStreamVolume(stream).toFloat() / audio.getStreamMaxVolume(stream).coerceAtLeast(1))
        if (pendingBrightness == null) brightnessState(android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
    }
    private fun has(name: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, name) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun request(name: String, reason: String) { explain(reason) { permission(name) } }
    private fun open(action: String, packageUri: Boolean = false) = attempt {
        context.startActivity(android.content.Intent(action).apply { if (packageUri) data = android.net.Uri.parse("package:${context.packageName}") })
    }
    private fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { explain("Unavailable: ${e.localizedMessage ?: "system could not complete this action"}", null) } }
    @Suppress("DEPRECATION")
    fun toggle(toggle: QuickToggle, on: Boolean) {
        attempt {
            when (toggle) {
                QuickToggle.Wifi -> open(android.provider.Settings.Panel.ACTION_WIFI)
                QuickToggle.Airplane -> open(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                QuickToggle.MobileData -> open(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                QuickToggle.Bluetooth -> if (!has(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                    request(android.Manifest.permission.BLUETOOTH_CONNECT, "Allow nearby devices to read and switch Bluetooth.")
                } else if (android.os.Build.VERSION.SDK_INT >= 33) open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                else if (bluetooth == null || !(if (on) bluetooth!!.enable() else bluetooth!!.disable())) explain("Bluetooth change was not accepted. Use Bluetooth settings.", { open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) })
                QuickToggle.Flashlight -> if (!has(android.Manifest.permission.CAMERA)) {
                    request(android.Manifest.permission.CAMERA, "Camera access is needed to use the flashlight.")
                } else torchId?.let { camera.setTorchMode(it, on) } ?: explain("No flashlight is available on this device.", null)
                QuickToggle.Silent, QuickToggle.Bedtime -> if (!notifications.isNotificationPolicyAccessGranted) {
                    explain("Allow Do Not Disturb access to control silent mode and Bedtime (Do Not Disturb).") { open(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
                } else if (toggle == QuickToggle.Silent) audio.ringerMode = if (on) android.media.AudioManager.RINGER_MODE_SILENT else android.media.AudioManager.RINGER_MODE_NORMAL
                else notifications.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                QuickToggle.QrScanner -> {
                    explain("Sidekit Camera does not scan QR codes yet. Opening a system scanner if installed.", null)
                    val scan = android.content.Intent("com.google.zxing.client.android.SCAN").putExtra("SCAN_MODE", "QR_CODE_MODE")
                    try {
                        context.startActivity(scan)
                    } catch (_: android.content.ActivityNotFoundException) {
                        explain("Opening the system camera. Use its QR mode if available; Sidekit Camera does not scan yet.", null)
                        open(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    }
                }
            }
            refresh()
        }
    }
    fun volume(value: Float) = attempt {
        if (stream == android.media.AudioManager.STREAM_RING && value <= 0f && !notifications.isNotificationPolicyAccessGranted) {
            explain("Allow Do Not Disturb access to silence the ringer.") { open(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
            return@attempt
        }
        audio.setStreamVolume(stream, (value.coerceIn(0f, 1f) * audio.getStreamMaxVolume(stream)).toInt(), 0)
        refresh()
    }
    fun brightness(value: Float) {
        if (!android.provider.Settings.System.canWrite(context)) {
            explain("Allow system settings access to change screen brightness.") { open(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
            return
        }
        pendingBrightness = value.coerceIn(0.01f, 1f)
        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = pendingBrightness!! } }
        brightnessState(pendingBrightness!!)
        handler.removeCallbacks(commitBrightness)
        // The shared slider has no release callback; debounce writes while the window previews.
        handler.postDelayed(commitBrightness, 250)
    }
    private fun restoreWindow() { activity?.window?.let { it.attributes = it.attributes.apply { screenBrightness = oldBrightness } } }
}
