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
import hrln.interactive.sidekit.design.theme.readableContentOn

enum class CaseColour(val label: String, val color: Color, private val success: Color? = null) {
    // Green accents need a teal commit colour, otherwise Primary and Secondary are two greens.
    Lime("Lime", Color(0xFFA2F49F), success = Color(0xFF0E7070)),
    Orange("Orange", Color(0xFFF68A02)),
    Blue("Blue", Color(0xFF82C7F3)),
    Pink("Pink", Color(0xFFF575D1)),
    Sage("Sage", Color(0xFF90C190), success = Color(0xFF0E7070)),
    Lavender("Lavender", Color(0xFFAE99D1));

    fun palette(): SidekitColors {
        val base = SidekitColors()
        return base.copy(
            primaryA1 = color,
            primaryA2 = lerp(color, Color.White, 0.12f),
            primaryA3 = lerp(color, Color.White, 0.24f),
            successA1 = success ?: base.successA1,
            successA2 = success?.let { lerp(it, Color.White, 0.30f) } ?: base.successA2,
            successA3 = success?.let { lerp(it, Color.White, 0.55f) } ?: base.successA3,
        )
    }
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
            caseColourChoices(selected) { prefs.caseColour = it }
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.caseColourChoices(selected: CaseColour?, choose: (CaseColour?) -> Unit) {
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
                                .selectable(active, role = Role.RadioButton, onClick = { choose(colour) })
                                .semantics { contentDescription = colour.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (active) SidekitIconImage(SidekitIcon.Check, tint = readableContentOn(colour.color))
                        }
                    }
                }
            }
        }
    }
    item { SidekitText("Active: ${selected?.label ?: "Original blue"}", style = SidekitTheme.typography.label) }
    item { SidekitLineButton("Original blue", { choose(null) }, variant = LineButtonVariant.Secondary) }
}
