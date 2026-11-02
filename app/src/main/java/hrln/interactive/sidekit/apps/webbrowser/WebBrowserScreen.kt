package hrln.interactive.sidekit.apps.webbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.apps.everyday.CompactRowHeight
import hrln.interactive.sidekit.apps.everyday.dualArrowActions
import hrln.interactive.sidekit.apps.everyday.EverydayContent
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SubApp
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

data class WebPage(val address: String, val site: String, val heading: String, val body: String, val link: String)

object WebBrowserSampleData {
    // Placeholder content from "Web browser / Home" (96:830).
    val page = WebPage(
        address = "example.com",
        site = "EXAMPLE.COM",
        heading = "A quieter web.",
        body = "Read, explore, and find what you need. One page at a time.",
        link = "Start reading →",
    )
}

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.webBrowserGraph(nav: NavHostController) {
    composable(SubApp.WebBrowser.route) { WebBrowserScreen(WebBrowserSampleData.page) }
}

/** "Web browser / Home" (96:830). "← Tabs" and "Search →" have no mocked destination. */
@Composable
fun WebBrowserScreen(page: WebPage, onTabs: () -> Unit = {}, onSearch: () -> Unit = {}) {
    SidekitScaffold(
        title = "Web browser",
        actions = dualArrowActions("Tabs", "Search", onTabs, onSearch),
    ) { padding ->
        EverydayContent(padding) {
            SidekitItem("Address", Modifier.height(CompactRowHeight), secondary = page.address)
            // "Example website" (96:886): 308 high, blocks 16 apart from y 18.
            SidekitTile(Modifier.fillMaxWidth().height(308.dp), contentAlignment = Alignment.TopStart) {
                Column(
                    Modifier.padding(start = SidekitTheme.spacing.itemPadding, end = SidekitTheme.spacing.itemPadding, top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SidekitText(page.site, style = SidekitTheme.typography.small, color = SidekitTheme.colors.surfaceA6)
                    SidekitText(page.heading, style = SidekitTheme.typography.headline)
                    // Two lines on a 38 pitch (76 high) in the mock; label size matches its wrap.
                    SidekitText(page.body, style = SidekitTheme.typography.label.copy(lineHeight = 38.sp), maxLines = 2)
                    SidekitText(page.link, style = SidekitTheme.typography.label)
                }
            }
        }
    }
}

@SidephonePreview
@Composable
private fun WebBrowserPreview() = SidekitPreviewFrame { WebBrowserScreen(WebBrowserSampleData.page) }
