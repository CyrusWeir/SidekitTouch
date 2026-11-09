package hrln.interactive.sidekit.apps.sideweather

import androidx.compose.foundation.background
import hrln.interactive.sidekit.design.action.SideAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import hrln.interactive.sidekit.design.components.listFade
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import hrln.interactive.sidekit.ui.SidephonePreview

/** Forecast rows in 94:385 / 94:447 are 74 high (five fit the 385 content area). */
private val ForecastRowHeight = 74.dp

/** Current-conditions card in 94:322 (460 x 171). */
private val ConditionsCardHeight = 171.dp

/** "Sideweather" (94:322). */
@Composable
fun SideweatherHomeScreen(
    current: CurrentConditions,
    summary: List<WeatherRow>,
    onHourly: () -> Unit,
    onFiveDay: () -> Unit,
    options: List<SideAction> = emptyList(),
) {
    SidekitScaffold(
        title = "Sideweather",
        options = options,
        actions = listOf(SideAction.back("back", "Hourly", onHourly), SideAction.forward("forward", "5 days", onFiveDay)),
    ) { padding ->
        val list = rememberLazyListState()
        SidekitList(padding, state = list) {
            item { Box(Modifier.listFade(list, 0)) { ConditionsCard(current) } }
            itemsIndexed(summary) { i, row -> SidekitItem(title = row.label, secondary = row.detail, modifier = Modifier.listFade(list, i + 1)) }
        }
    }
}

@Composable
private fun ConditionsCard(current: CurrentConditions) {
    val colors = SidekitTheme.colors
    val type = SidekitTheme.typography
    Column(
        Modifier
            .fillMaxWidth()
            .height(ConditionsCardHeight)
            .clip(SidekitTheme.shapes.item)
            .background(colors.surfaceA3)
            .padding(horizontal = SidekitTheme.spacing.itemPadding)
            .padding(top = 14.dp),
    ) {
        SidekitText(current.place, style = type.caption)
        Spacer(Modifier.height(2.dp))
        Row {
            SidekitText(current.temperature, style = type.display)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.padding(top = 7.dp)) {
                SidekitText(current.sky)
                Spacer(Modifier.height(5.dp))
                SidekitText(current.feelsLike, style = type.small, color = colors.surfaceA6)
            }
        }
    }
}

/** "Hourly forecast" (94:385) and "5-day forecast" (94:447). */
@Composable
fun ForecastScreen(title: String, rows: List<WeatherRow>, onBack: () -> Unit, onLocations: () -> Unit, place: String? = null) {
    SidekitScaffold(
        title = title,
        actions = listOf(SideAction.back("back", "Back", onBack), SideAction.forward("forward", "Locations", onLocations)),
    ) { padding ->
        val list = rememberLazyListState()
        val offset = if (place != null) 1 else 0
        SidekitList(padding, state = list) {
            // Which place this forecast is for, captioned like the home card.
            if (place != null) item {
                Box(Modifier.fillMaxWidth().padding(horizontal = SidekitTheme.spacing.itemPadding, vertical = 6.dp).listFade(list, 0)) {
                    SidekitText(place, style = SidekitTheme.typography.caption, maxLines = 1)
                }
            }
            itemsIndexed(rows) { i, row -> SidekitItem(title = row.label, secondary = row.detail, modifier = Modifier.height(ForecastRowHeight).listFade(list, i + offset)) }
        }
    }
}

/** "Locations" (94:509). "Add" has no mocked destination, so it's a no-op. */
@Composable
fun LocationsScreen(locations: List<WeatherRow>, hint: String, onBack: () -> Unit, onAdd: () -> Unit) {
    SidekitScaffold(
        title = "Locations",
        actions = listOf(SideAction.back("back", "Back", onBack), SideAction.forward("forward", "Add", onAdd)),
    ) { padding ->
        SidekitList(padding) {
            items(locations) { SidekitItem(title = it.label, secondary = it.detail) }
            item {
                // "Location hint" frame: 460 x 64, text inset 30.
                Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = SidekitTheme.spacing.itemPadding), contentAlignment = Alignment.CenterStart) {
                    SidekitText(hint, style = SidekitTheme.typography.small, color = SidekitTheme.colors.surfaceA6)
                }
            }
        }
    }
}

@SidephonePreview
@Composable
private fun SideweatherHomePreview() = SidekitPreviewFrame {
    SideweatherHomeScreen(SideweatherSampleData.current, SideweatherSampleData.summary, {}, {})
}

@SidephonePreview
@Composable
private fun HourlyPreview() = SidekitPreviewFrame {
    ForecastScreen("Hourly forecast", SideweatherSampleData.hourly, {}, {})
}

@SidephonePreview
@Composable
private fun FiveDayPreview() = SidekitPreviewFrame {
    ForecastScreen("5-day forecast", SideweatherSampleData.fiveDay, {}, {})
}

@SidephonePreview
@Composable
private fun LocationsPreview() = SidekitPreviewFrame {
    LocationsScreen(SideweatherSampleData.locations, SideweatherSampleData.locationHint, {}, {})
}
