package hrln.interactive.sidekit.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** Control-centre toggle (53:657 / 53:674): green when on, surface when off. */
@Composable
fun SidekitToggleTile(
    icon: SidekitIcon,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SidekitTheme.colors
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(SidekitTheme.shapes.item)
            .background(if (checked) colors.successA1 else colors.surfaceA3)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SidekitIconImage(icon, size = SidekitTheme.spacing.tileIconSize)
    }
}

/** Control-centre level item (Volume / Brightness, 53:691 / 53:696). [value] is 0..1. */
@Composable
fun SidekitSliderItem(
    icon: SidekitIcon,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SidekitTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SidekitTheme.spacing.itemHeight)
            .clip(SidekitTheme.shapes.item)
            .background(colors.surfaceA3)
            .pointerInput(Unit) {
                detectTapGestures { onValueChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { onValueChange(it.coerceIn(0f, 1f)); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .background(colors.primaryA1),
        )
        Row(
            modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap),
        ) {
            SidekitIconImage(icon)
            SidekitText(label)
        }
    }
}

/** Sideticks tick box (1:422): outlined when open, filled when ticked. */
@Composable
fun SidekitTickBox(checked: Boolean, modifier: Modifier = Modifier) {
    val light = SidekitTheme.colors.light
    Box(
        modifier
            .size(44.dp)
            .clip(SidekitTheme.shapes.control)
            .then(if (checked) Modifier.background(light) else Modifier.border(3.dp, light, SidekitTheme.shapes.control)),
    )
}
