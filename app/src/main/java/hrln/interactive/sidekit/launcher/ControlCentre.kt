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
            QuickToggle.entries.chunked(4).forEach { row ->
                SidekitItemRow {
                    row.forEach { toggle ->
                        SidekitToggleTile(
                            icon = toggle.icon,
                            label = toggle.label,
                            checked = state.toggles[toggle] == true,
                            onCheckedChange = { onToggle(toggle, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            SidekitSliderItem(SidekitIcon.BrandAwareness, "Volume", state.volume, onVolumeChange)
            SidekitSliderItem(SidekitIcon.LightMode, "Brightness", state.brightness, onBrightnessChange)
        }
    }
}
