package hrln.interactive.sidekit.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.theme.SidekitTheme

internal data class DemoKey(val label: String, val action: String)
internal fun demoRows(module: KeypadModule): List<List<DemoKey>> {
    fun key(label: String, action: String) = DemoKey(label, action)
    val navigation = listOf(key("Back", "Top-left: Back. Hold to open the dialer. Enable Sidekit keypad in Accessibility first."), key("Home", "Return to your home app."), key("Options", "Top-right: reveal this screen’s extra actions."))
    val arrows = listOf(key("← ↑ ↓ →", "Move focus between controls. In text, left and right move the cursor or suggestion selection."), key("OK", "Activate the focused control or confirm a suggestion."))
    return when (module) {
        KeypadModule.T9 -> listOf(navigation, arrows) + listOf(
            listOf("1", "2 abc", "3 def"),
            listOf("4 ghi", "5 jkl", "6 mno"),
            listOf("7 pqrs", "8 tuv", "9 wxyz"),
            listOf("*", "0", "#")
        ).map { row ->
            row.map { label ->
                key(
                    label,
                    when (label) {
                        "*" -> "Tap * repeatedly to cycle punctuation and symbols."
                        "#" -> "Tap # to change letter case. Hold # to switch letters / numbers."
                        "0" -> "Tap 0 for a space in letter mode. Hold for the digit 0."
                        else -> "Tap repeatedly to cycle this key’s characters. Hold to type its digit."
                    }
                )
            }
        } + listOf(listOf(key("Delete", "Delete the previous character.")))

        KeypadModule.Qwerty -> listOf(navigation) + listOf(
            listOf("QW", "ER", "TY", "UI", "OP"),
            listOf("AS", "DF", "GH", "JK", "L"),
            listOf("ZX", "CV", "BN", "M", "Delete")
        ).map { row -> row.map { key(it, if (it == "Delete") "Delete the previous character." else "Tap repeatedly to choose a letter from $it. Hold for the digit or symbol printed above the key.") } } + listOf(
            listOf(key("↕", "Cycle the suggested words."), key("SYM", "Tap repeatedly to cycle symbols."), key("aA", "Tap to change letter case. Hold to switch letters / numbers.")),
            listOf(key("Space", "Insert a space. Hold to type 0."), key("Enter", "Confirm a suggestion or insert a new line."))
        )

        // The Sundial is a ring of eight keys around the centre key. Lay it out exactly as it sits
        // on the module: top-left is left, top-right is right, bottom-left is Tab, bottom-right is
        // Enter, and the media keys are the middle row.
        KeypadModule.Sundial -> listOf(
            listOf(key("←", "Move focus left."), key("↑", "Move focus up."), key("→", "Move focus right.")),
            listOf(key("Previous", "Previous media track; hold for left."), key("Play / pause", "Play or pause media where supported."), key("Next", "Next media track; hold for right.")),
            listOf(key("Tab", "Move to the next focusable control; hold for Back."), key("↓", "Move focus down."), key("Enter", "Activate the focused control; hold for Options."))
        )

        KeypadModule.GamePad -> listOf(
            listOf(key("D-pad", "Move focus using the directional pad.")),
            listOf(key("Y", "Game action: its meaning depends on the app or game.")),
            listOf(key("X", "Game action: its meaning depends on the app or game."), key("A", "Confirm where the app supports controller input."), key("B", "Back where the app supports controller input.")),
            listOf(key("Shoulder", "Game action: its meaning depends on the app or game."))
        )
    }
}

@Composable
internal fun KeypadDemo(module: KeypadModule, idleHint: String = "Tap a key to learn its action. This demo stays in setup.") {
    var selected by rememberSaveable(module) { mutableStateOf<String?>(null) }
    val rows = remember(module) { demoRows(module) }
    val caption = SidekitTheme.typography.label.copy(fontSize = 20.sp, lineHeight = 21.sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SidekitText(rows.flatten().firstOrNull { it.label == selected }?.action ?: idleHint, style = caption, maxLines = 3, modifier = Modifier.height(64.dp))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    SidekitTile(Modifier.weight(1f).height(34.dp), color = if (selected == key.label) SidekitTheme.colors.primaryA1 else SidekitTheme.colors.surfaceA3, onClick = { selected = key.label }) {
                        SidekitText(key.label, style = caption)
                    }
                }
            }
        }
        if (module == KeypadModule.Sundial) {
            SidekitText(
                "No Back or Options key: hold Tab for Back and Enter for Options. The corner arrows move left/right; hold Previous/Next for the same, and a tap is still media.",
                style = SidekitTheme.typography.label.copy(fontSize = 16.sp, lineHeight = 18.sp),
                maxLines = Int.MAX_VALUE
            )
        }
        if (!module.typesOnKeypad) SidekitText("Text fields use the on-screen keyboard with this module.", style = caption, maxLines = Int.MAX_VALUE)
    }
}
