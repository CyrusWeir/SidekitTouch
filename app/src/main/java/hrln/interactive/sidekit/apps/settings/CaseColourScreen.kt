package hrln.interactive.sidekit.apps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitColors
import hrln.interactive.sidekit.design.theme.SidekitTheme

enum class CaseColour(val label: String, val color: Color) {
    Lime("Lime", Color(0xFFA2F49F)),
    Orange("Orange", Color(0xFFF68A02)),
    Blue("Blue", Color(0xFF82C7F3)),
    Pink("Pink", Color(0xFFF575D1)),
    Sage("Sage", Color(0xFF90C190)),
    Lavender("Lavender", Color(0xFFAE99D1));

    fun palette() = SidekitColors(
        primaryA1 = color,
        primaryA2 = lerp(color, Color.White, 0.12f),
        primaryA3 = lerp(color, Color.White, 0.24f),
    )
}

@Composable
internal fun rememberCaseColour(prefs: SidekitPreferences): CaseColour? {
    var selected by remember(prefs) { mutableStateOf(prefs.caseColour) }
    DisposableEffect(prefs) {
        val stop = prefs.observeCaseColour { selected = prefs.caseColour }
        selected = prefs.caseColour
        onDispose { stop() }
    }
    return selected
}

@Composable
internal fun CaseColourScreen(back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { SidekitPreferences(context) }
    val selected = rememberCaseColour(prefs)
    SidekitScaffold("Case colour", actions = listOf(SideAction.back("back", "Settings", back))) { padding ->
        SidekitList(padding) {
            item { SidekitText("Match your Sidephone case", style = SidekitTheme.typography.label) }
            item {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap)) {
                    CaseColour.entries.chunked(3).forEach { row ->
                        SidekitItemRow {
                            row.forEach { colour ->
                                val active = colour == selected
                                Box(
                                    Modifier.weight(1f).height(88.dp)
                                        .border(if (active) 3.dp else 0.dp, if (active) Color.White else Color.Transparent, SidekitTheme.shapes.item)
                                        .padding(6.dp)
                                        .clip(SidekitTheme.shapes.item)
                                        .background(colour.color)
                                        .selectable(active, role = Role.RadioButton, onClick = { prefs.caseColour = colour })
                                        .semantics { contentDescription = colour.label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (active) SidekitIconImage(SidekitIcon.Check, tint = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
            item { SidekitText("Active: ${selected?.label ?: "Original blue"}", style = SidekitTheme.typography.label) }
            item { SidekitLineButton("Original blue", { prefs.caseColour = null }, variant = LineButtonVariant.Secondary) }
        }
    }
}
