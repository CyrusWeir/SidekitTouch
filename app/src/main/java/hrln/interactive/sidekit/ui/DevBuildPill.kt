package hrln.interactive.sidekit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hrln.interactive.sidekit.R
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.contentColorFor

/**
 * The dev build's marker: a small "DEV" pill in the right-hand corner of the launcher header.
 *
 * Only the `dev` build type sets a non-empty `build_label`; stable builds pass an empty label and
 * draw nothing, which also keeps every recorded screenshot unchanged. It is a marker, not a
 * control: no click, no focus (so it stays out of the D-pad order and the focus tests), and a
 * single spoken label - "Development build" - rather than spelling out the letters.
 */
@Composable
fun DevBuildPill(label: String, modifier: Modifier = Modifier) {
    val background = SidekitTheme.colors.surfaceA4
    val description = stringResource(R.string.build_pill_description)
    Box(
        modifier
            .clip(SidekitTheme.shapes.control)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        SidekitText(
            text = label,
            // `tiny` with a little tracking reads as a tag rather than as a word in the header.
            style = SidekitTheme.typography.tiny.copy(fontWeight = FontWeight.Medium, letterSpacing = 2.sp),
            color = SidekitTheme.colors.contentColorFor(background)
        )
    }
}
