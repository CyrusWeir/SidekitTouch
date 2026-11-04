package hrln.interactive.sidekit.apps.tripplanning

import android.content.Context
import hrln.interactive.sidekit.apps.maps.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Trip(val id: String, val from: String, val to: String, val time: String, val notes: String, val origin: TransitStop? = null, val destination: TransitStop? = null, val itinerary: String = "")
data class TripDraft(val from: String, val to: String, val time: String, val notes: String)

/** Saves an itinerary or a personal plan after live options have been chosen separately. */
interface TripPlanner { suspend fun plan(draft: TripDraft): Trip }
class LocalTripPlanner : TripPlanner {
    override suspend fun plan(draft: TripDraft): Trip {
        require(draft.from.isNotBlank() && draft.to.isNotBlank()) { "Enter a start and destination" }
        return Trip(UUID.randomUUID().toString(), draft.from.trim(), draft.to.trim(), draft.time.trim(), draft.notes.trim())
    }
}
data class TripData(val trips: List<Trip> = emptyList(), val places: List<Place> = emptyList())
class TripRepository(file: File, scope: CoroutineScope) {
    val store = OutdoorStore(file, ::decode, ::encode, TripData(), scope)
    fun save(trip: Trip) = store.edit { it.copy(trips = (listOf(trip) + it.trips.filterNot { t -> t.id == trip.id }).take(500)) }
    fun delete(trip: Trip) = store.edit { it.copy(trips = it.trips.filterNot { t -> t.id == trip.id }) }
    fun savePlace(place: Place) = store.edit { it.copy(places = (it.places.filterNot { p -> p.key == place.key } + place).takeLast(100)) }
    fun deletePlace(place: Place) = store.edit { it.copy(places = it.places.filterNot { p -> p.key == place.key }) }
    companion object {
        fun encode(data: TripData) = JSONObject().put("trips", JSONArray(data.trips.map { JSONObject().put("id", it.id).put("from", it.from).put("to", it.to).put("time", it.time).put("notes", it.notes).put("origin", it.origin?.json()).put("destination", it.destination?.json()).put("itinerary", it.itinerary) })).put("places", JSONArray(data.places.map { it.json() }))
        fun decode(j: JSONObject): TripData {
            val trips = j.getJSONArray("trips"); val places = j.getJSONArray("places")
            return TripData(List(trips.length()) { trips.getJSONObject(it).let { t -> Trip(t.getString("id"), t.getString("from"), t.getString("to"), t.getString("time"), t.getString("notes"), t.optJSONObject("origin")?.stop(), t.optJSONObject("destination")?.stop(), t.optString("itinerary")) } }, List(places.length()) { Place.parse(places.getJSONObject(it)) })
        }
        private var instance: TripRepository? = null
        @Synchronized fun get(context: Context): TripRepository = instance ?: TripRepository(File(context.filesDir, "trips.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
    }
}

private fun TransitStop.json() = JSONObject().put("id",id).put("name",name).put("type",type)
private fun JSONObject.stop() = TransitStop(getString("id"),getString("name"),getString("type"))
