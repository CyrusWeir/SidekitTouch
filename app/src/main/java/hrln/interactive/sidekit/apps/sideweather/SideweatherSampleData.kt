package hrln.interactive.sidekit.apps.sideweather

/** A row with a primary label and secondary detail ("Now" · "24°  Sunny"). */
data class WeatherRow(val label: String, val detail: String)

data class CurrentConditions(
    val place: String,
    val temperature: String,
    val sky: String,
    val feelsLike: String,
)

/** Placeholder content copied from the Sideweather mocks (section 94:321). */
object SideweatherSampleData {
    // Sideweather 94:322
    val current = CurrentConditions(place = "Sydney · Now", temperature = "24°", sky = "Sunny", feelsLike = "Feels like 25°")
    val summary = listOf(
        WeatherRow("High 26°", "Low 18°"),
        WeatherRow("Rain 5%", "Wind 14 km/h"),
    )

    // Hourly forecast 94:385
    val hourly = listOf(
        WeatherRow("Now", "24°  Sunny"),
        WeatherRow("1 pm", "25°  Sunny"),
        WeatherRow("2 pm", "26°  Sunny"),
        WeatherRow("3 pm", "25°  Cloudy"),
        WeatherRow("4 pm", "23°  Cloudy"),
    )

    // 5-day forecast 94:447
    val fiveDay = listOf(
        WeatherRow("Today", "26° / 18°"),
        WeatherRow("Tue", "24° / 17°"),
        WeatherRow("Wed", "22° / 16°"),
        WeatherRow("Thu", "23° / 17°"),
        WeatherRow("Fri", "25° / 18°"),
    )

    // Locations 94:509
    val locations = listOf(
        WeatherRow("Sydney", "24°  Sunny"),
        WeatherRow("Melbourne", "18°  Cloudy"),
        WeatherRow("Brisbane", "28°  Sunny"),
    )
    const val locationHint = "Sydney is your current location"
}
