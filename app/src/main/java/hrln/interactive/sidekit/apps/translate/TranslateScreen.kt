package hrln.interactive.sidekit.apps.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.translateGraph(nav: NavHostController) {
    composable(SubApp.Translate.route) {
        val vm: TranslateViewModel = viewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        TranslateScreen(state, onMic = vm::onMic)
    }
}

/** Translate icons and mic glyphs in 1:910 are 44 px, with the language label 12 px after the icon. */
private val TranslateIconSize = 44.dp
private val TranslateIconGap = 12.dp

/**
 * "Translate Base Voice Base" (1:910), "Voice Mode Talking" (1:837) and, once a phrase has been
 * "heard", "Large prompt" (1:872) / its scrolled state (1:960). No action bar.
 */
@Composable
fun TranslateScreen(state: TranslateUiState, onMic: (TranslateSide) -> Unit) {
    SidekitScaffold(title = "Translate") { padding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemOuterGap),
        ) {
            TranslateSide.entries.forEach { side ->
                val pane = state.pane(side)
                LanguageRow(pane.language, talking = state.talking == side, onMic = { onMic(side) })
                TextRow(pane.text)
            }
        }
    }
}

@Composable
private fun LanguageRow(language: String, talking: Boolean, onMic: () -> Unit) {
    val colors = SidekitTheme.colors
    SidekitItemRow {
        SidekitTile(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TranslateIconGap),
            ) {
                SidekitIconImage(SidekitIcon.Translate, size = TranslateIconSize)
                SidekitText(language)
            }
        }
        SidekitIconItem(
            icon = if (talking) SidekitIcon.MicOff else SidekitIcon.Mic,
            contentDescription = if (talking) "Stop listening to $language" else "Speak $language",
            modifier = Modifier.size(SidekitTheme.spacing.itemHeight),
            color = if (talking) colors.dangerA2 else colors.primaryA1,
            iconSize = TranslateIconSize,
            onClick = onMic,
        )
    }
}

@Composable
private fun TextRow(text: String) {
    SidekitCard(
        modifier = Modifier.fillMaxWidth().heightIn(min = SidekitTheme.spacing.itemHeight),
        color = SidekitTheme.colors.surfaceA2,
    ) {
        SidekitText(text, color = SidekitTheme.colors.surfaceA6, maxLines = Int.MAX_VALUE)
    }
}

@SidephonePreview
@Composable
private fun TranslateBasePreview() = SidekitPreviewFrame { TranslateScreen(TranslateUiState(), {}) }

@SidephonePreview
@Composable
private fun TranslateTalkingPreview() = SidekitPreviewFrame {
    TranslateScreen(TranslateUiState(talking = TranslateSide.Target), {})
}

@SidephonePreview
@Composable
private fun TranslateLargePromptPreview() = SidekitPreviewFrame {
    TranslateScreen(TranslateUiState().toggleMic(TranslateSide.Source).toggleMic(TranslateSide.Source), {})
}
