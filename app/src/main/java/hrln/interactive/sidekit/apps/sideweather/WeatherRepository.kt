package hrln.interactive.sidekit.apps.sideweather

import android.content.Context
import android.util.AtomicFile
import hrln.interactive.sidekit.apps.maps.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class WeatherSettings(val places: List<Place> = emptyList(), val selected: String? = null, val fahrenheit: Boolean = false)
data class Forecast(val received: Long, val data: JSONObject) {
    fun stale(now: Long) = now - received !in 0 until 30 * 60_000L
    fun temperature(c: Double, imperial: Boolean) = "${(if (imperial) c * 1.8 + 32 else c).roundToInt()}°"
    fun current(place: Place, imperial: Boolean): CurrentConditions {
        val c = data.getJSONObject("current")
        return CurrentConditions(place.name, temperature(c.getDouble("temperature_2m"), imperial), sky(c.getInt("weather_code")), "Feels ${temperature(c.getDouble("apparent_temperature"), imperial)}")
    }
    fun summary(imperial: Boolean): List<WeatherRow> {
        val d = data.getJSONObject("daily")
        val c = data.getJSONObject("current")
        val wind = c.getDouble("wind_speed_10m")
        return listOf(WeatherRow("High ${temperature(d.getJSONArray("temperature_2m_max").getDouble(0), imperial)}", "Low ${temperature(d.getJSONArray("temperature_2m_min").getDouble(0), imperial)}"), WeatherRow("Rain ${d.getJSONArray("precipitation_probability_max").optInt(0)}%", "Wind ${(if (imperial) wind / 1.609344 else wind).roundToInt()} ${if (imperial) "mph" else "km/h"}"))
    }
    fun hourly(imperial: Boolean): List<WeatherRow> {
        val h = data.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val now = data.getJSONObject("current").getString("time").take(13)
        return (0 until times.length()).filter { times.getString(it).take(13) >= now }.take(24).map {
            WeatherRow(java.time.LocalDateTime.parse(times.getString(it)).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")), "${temperature(h.getJSONArray("temperature_2m").getDouble(it), imperial)} ${sky(h.getJSONArray("weather_code").getInt(it))}")
        }
    }
    fun daily(imperial: Boolean): List<WeatherRow> {
        val d = data.getJSONObject("daily")
        return (0 until minOf(5, d.getJSONArray("time").length())).map {
            WeatherRow(if (it == 0) "Today" else java.time.LocalDate.parse(d.getJSONArray("time").getString(it)).format(java.time.format.DateTimeFormatter.ofPattern("EEE")), "${temperature(d.getJSONArray("temperature_2m_max").getDouble(it), imperial)} / ${temperature(d.getJSONArray("temperature_2m_min").getDouble(it), imperial)}")
        }
    }
}
fun sky(code: Int) = when (code) { 0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"; in 51..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> "Unknown" }

class WeatherRepository(private val directory: File, scope: CoroutineScope,
                        private val fetch: suspend (String) -> JSONObject = ::fetchJson,
                        private val clock: () -> Long = System::currentTimeMillis) {
    val settings = OutdoorStore(File(directory, "weather-places.json"), { j ->
        val a = j.getJSONArray("places")
        WeatherSettings(List(a.length()) { Place.parse(a.getJSONObject(it)) }, j.optString("selected").takeIf(String::isNotBlank), j.optBoolean("fahrenheit"))
    }, { s: WeatherSettings -> JSONObject().put("places", JSONArray(s.places.map { it.json() })).put("selected", s.selected.orEmpty()).put("fahrenheit", s.fahrenheit) }, WeatherSettings(), scope)
    private val memory = android.util.LruCache<String, Forecast>(6)
    private fun file(place: Place) = AtomicFile(File(directory, "weather-${place.key}.json"))
    suspend fun cached(place: Place): Forecast? = withContext(Dispatchers.IO) {
        synchronized(memory) { memory.get(place.key) } ?: try {
            val j = file(place).openRead().bufferedReader().use { JSONObject(it.readText()) }
            Forecast(j.getLong("received"), j.getJSONObject("data")).also { synchronized(memory) { memory.put(place.key, it) } }
        } catch (_: Exception) { null }
    }
    suspend fun refresh(place: Place): Forecast {
        cached(place)?.takeUnless { it.stale(clock()) }?.let { return it }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&hourly=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=5"
        val result = Forecast(clock(), fetch(url))
        // Parse before replacing a usable cached response.
        result.current(place, false); result.summary(false); result.hourly(false); result.daily(false)
        withContext(Dispatchers.IO) {
            val disk = file(place)
            val out = disk.startWrite()
            try { out.write(JSONObject().put("received", result.received).put("data", result.data).toString().toByteArray()); disk.finishWrite(out) }
            catch (e: Exception) { disk.failWrite(out); throw e }
            synchronized(memory) { memory.put(place.key, result) }
            directory.listFiles()?.filter { it.name.startsWith("weather-") && it.name != "weather-places.json" && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
        }
        return result
    }
    /** A new current-location fix replaces the previous one rather than piling up. */
    fun select(place: Place) = settings.edit { it.copy(places = (it.places.filterNot { p -> p.key == place.key || (place.isCurrentLocation && p.isCurrentLocation) } + place).takeLast(30), selected = place.key) }
    /** Renames a saved place in place, without changing which one is selected. */
    fun rename(place: Place, name: String) = settings.edit { it.copy(places = it.places.map { p -> if (p.key == place.key) p.copy(name = name) else p }) }
    fun delete(place: Place) = settings.edit { val places = it.places.filterNot { p -> p.key == place.key }; it.copy(places = places, selected = if (it.selected == place.key) places.firstOrNull()?.key else it.selected) }
    companion object {
        @Volatile private var instance: WeatherRepository? = null
        fun get(context: Context): WeatherRepository = instance ?: synchronized(this) { instance ?: WeatherRepository(context.applicationContext.filesDir, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it } }
    }
}
