package hrln.interactive.sidekit.apps.tripplanning

import hrln.interactive.sidekit.apps.integrations.JsonHttp
import hrln.interactive.sidekit.apps.integrations.JsonRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter

private val SYDNEY = ZoneId.of("Australia/Sydney")
data class TransitStop(val id: String, val name: String, val type: String)
data class TransitLeg(val route: String, val from: String, val to: String, val departure: String, val arrival: String, val live: Boolean)
data class TransitJourney(val id: String, val departure: String, val arrival: String, val legs: List<TransitLeg>, val live: Boolean)
data class TransitDeparture(val route: String, val destination: String, val planned: String, val estimated: String?, val platform: String, val cancelled: Boolean) {
    val live get() = estimated != null
}
fun transitTime(value: String): String = runCatching { Instant.parse(value).atZone(SYDNEY).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault(value)

fun savedTripTime(value: String): String = value.split(" – ").joinToString(" – ") { part ->
    runCatching { Instant.parse(part).atZone(SYDNEY).format(DateTimeFormatter.ofPattern("dd MMM HH:mm")) }.getOrDefault(part)
}

/** Same departure REST service as 392Fan; journeys use the current transportnsw.info site's query. */
class NswTransit(private val http: JsonRequest = JsonHttp, private val now: () -> ZonedDateTime = { ZonedDateTime.now(SYDNEY) }) {
    suspend fun stops(name: String): List<TransitStop> = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Enter a stop or station" }
        val result = http.request("$BASE/stop-finder-request?name=${encode(name.trim())}&type=stop&coordOutputFormat=EPSG:4326", null, null)
        result.optJSONArray("locations").objects().map { TransitStop(it.getString("id"), it.getString("name"), it.optString("type", "stop")) }.distinctBy { it.id }.take(30)
    }
    suspend fun journeys(from: TransitStop, to: TransitStop, time: String): List<TransitJourney> = withContext(Dispatchers.IO) {
        val date = if (time.isBlank()) now() else try { LocalDateTime.parse(time.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(SYDNEY) }
            catch (_: Exception) { throw IllegalArgumentException("Use Sydney time: yyyy-MM-dd HH:mm, or leave blank for now") }
        val input = JSONObject().put("tripMode", "public").put("from", from.id).put("to", to.id)
            .put("typeOrigin", from.type).put("typeDestination", to.type).put("departureDateTime", date.toOffsetDateTime().toString())
        val response = http.request("https://transportnsw.info/api/graphql", null, JSONObject().put("query", QUERY).put("variables", JSONObject().put("input", input)))
        if (response.has("errors")) throw IOException("Journey service could not plan this trip. Try different stops.")
        response.getJSONObject("data").getJSONObject("widgets").optJSONArray("tripOptions").objects().map { j ->
            TransitJourney(j.getString("id"), j.getString("departureTime"), j.getString("arrivalTime"), j.getJSONArray("legs").objects().map { l ->
                TransitLeg(l.optString("routeNumber").takeUnless { it == "null" || it.isBlank() } ?: if (l.optInt("mode") == 100) "Walk" else "Transit",
                    l.getJSONObject("origin").getString("name"), l.getJSONObject("destination").getString("name"), l.getString("departureTime"), l.getString("arrivalTime"), l.optBoolean("isRealTime"))
            }, j.optBoolean("isRealTime"))
        }
    }
    suspend fun departures(stop: TransitStop): List<TransitDeparture> = withContext(Dispatchers.IO) {
        val date = now().withZoneSameInstant(SYDNEY)
        val url = "$BASE/departure-list-request?name=${encode(stop.id)}&type=${encode(stop.type)}&date=${date.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)}&time=${date.format(DateTimeFormatter.ofPattern("HHmm"))}&depArrMacro=dep&depType=stopEvents&accessible=null&debug=false"
        http.request(url, null, null).optJSONArray("stopEvents").objects().map { e ->
            val t = e.getJSONObject("transportation")
            TransitDeparture(t.optString("number", t.optString("disassembledName")), t.optJSONObject("destination")?.optString("name").orEmpty(),
                e.getString("departureTimePlanned"), e.optString("departureTimeEstimated").takeUnless { it.isBlank() || it == "null" },
                e.optJSONObject("location")?.optString("name").orEmpty(), e.optBoolean("isCancelled"))
        }.sortedBy { it.estimated ?: it.planned }.take(40)
    }
    companion object {
        const val BASE = "https://transportnsw.info/api/trip/v1"
        const val QUERY = "query SidekitJourneys(\$input: TripPlannerInput!){widgets{tripOptions(input:\$input){id departureTime arrivalTime isRealTime legs{mode routeNumber origin{name} destination{name} departureTime arrivalTime isRealTime}}}}"
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
