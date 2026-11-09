package hrln.interactive.sidekit.apps.maps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class Place(val name: String, val latitude: Double, val longitude: Double) {
    val key get() = "$latitude,$longitude"
    fun json() = JSONObject().put("name", name).put("lat", latitude).put("lon", longitude)
    companion object {
        fun parse(j: JSONObject) = Place(j.getString("name"), j.getDouble("lat"), j.getDouble("lon"))
    }
}

/** Small app stores: IO loading, immutable snapshots, conflated atomic writes. */
class OutdoorStore<T>(file: File, private val decode: (JSONObject) -> T, private val encode: (T) -> JSONObject,
                      initial: T, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) {
    private val disk = AtomicFile(file)
    private val mutable = MutableStateFlow<T?>(null)
    private val pendingEdits = mutableListOf<(T) -> T>()
    val state = mutable.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private val persisted = MutableStateFlow<T?>(null)
    suspend fun flush() { val value = mutable.value ?: return; persisted.first { it == value } }
    init {
        scope.launch(io) {
            val loaded = try { disk.openRead().bufferedReader().use { decode(JSONObject(it.readText())) } }
                catch (_: java.io.FileNotFoundException) { initial }
                catch (_: Exception) { error.value = "Could not read saved data"; initial }
            synchronized(this@OutdoorStore) {
                mutable.value = pendingEdits.fold(loaded) { value, change -> change(value) }
                pendingEdits.clear()
            }
            persisted.value = loaded
            var written = loaded
            mutable.filterNotNull().conflate().collect { value ->
                if (value != written) {
                    var out: java.io.FileOutputStream? = null
                    try {
                        out = disk.startWrite()
                        out.write(encode(value).toString().toByteArray())
                        disk.finishWrite(out)
                        written = value
                        persisted.value = value
                        error.value = null
                    } catch (_: Exception) { out?.let(disk::failWrite); error.value = "Could not save; please retry" }
                }
            }
        }
    }
    @Synchronized
    fun edit(change: (T) -> T) {
        val current = mutable.value
        if (current == null) pendingEdits += change else mutable.value = change(current)
    }
}

const val OUTDOOR_AGENT = "Sidekit/1.0 (Android; hrln.interactive.sidekit)"

suspend fun fetchJson(url: String): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { connection.disconnect() } }
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", OUTDOOR_AGENT)
        check(connection.responseCode == 200) { "Service unavailable (${connection.responseCode})" }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        ensureActive()
        JSONObject(text)
    } finally { cancellation.cancel(); connection.disconnect() }
}

suspend fun searchPlaces(query: String): List<Place> {
    if (query.trim().length < 2) return emptyList()
    val json = fetchJson("https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(query.trim())}&count=10&language=en&format=json")
    val rows = json.optJSONArray("results") ?: return emptyList()
    return List(rows.length()) { i -> rows.getJSONObject(i).let {
        Place(listOf(it.getString("name"), it.optString("admin1"), it.optString("country")).filter(String::isNotBlank).distinct().joinToString(", "), it.getDouble("latitude"), it.getDouble("longitude"))
    } }
}

fun hasLocation(context: Context) = hasPreciseLocation(context) ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
fun hasPreciseLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * A bounded single fix. Cancellation (including STOP) unregisters the platform request. GPS/fused
 * exact fixes need ACCESS_FINE_LOCATION; with only coarse location the network provider is the
 * only legal source (and is absent on devices that rely on the fused provider). Providers known to
 * be unavailable are skipped, so a SecurityException from a private provider is not the final word.
 */
suspend fun locate(context: Context): Place? = withTimeoutOrNull(15_000) {
    if (!hasLocation(context)) return@withTimeoutOrNull null
    val manager = context.getSystemService(LocationManager::class.java)
    val fine = hasPreciseLocation(context)
    val providers = (if (fine) listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                     else listOf(LocationManager.NETWORK_PROVIDER)).filter { manager.isProviderEnabled(it) }
    // Request providers together so a slow network fix cannot consume the GPS timeout.
    val fix = channelFlow<android.location.Location?> {
        providers.forEach { provider -> launch {
            val location = suspendCancellableCoroutine<android.location.Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: SecurityException) {
                    continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    continuation.resume(null)
                }
            }
            send(location)
        } }
    }.firstOrNull { it != null }
    fix?.let { Place(CURRENT_LOCATION, it.latitude, it.longitude) }
}

const val CURRENT_LOCATION = "Current location"

/** "Current location · Madrid", or just "Current location" when the town is unknown. */
fun currentLocationName(city: String?) = if (city.isNullOrBlank()) CURRENT_LOCATION else "$CURRENT_LOCATION · $city"

/** A fix from [locate], whether or not its town has been looked up yet. */
val Place.isCurrentLocation get() = name == CURRENT_LOCATION || name.startsWith("$CURRENT_LOCATION · ")

/**
 * The town or city at [place], from the platform geocoder. Null when the device has no geocoder,
 * is offline, or nothing comes back within a few seconds.
 */
suspend fun cityName(context: Context, place: Place): String? = withTimeoutOrNull(5_000) {
    if (!Geocoder.isPresent()) return@withTimeoutOrNull null
    val geocoder = Geocoder(context)
    val address: Address? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(place.latitude, place.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { if (continuation.isActive) continuation.resume(addresses.firstOrNull()) }
                override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resume(null) }
            })
        } else suspendCancellableCoroutine { continuation ->
            // Not withContext(IO): that would wait out the blocking call even after the timeout,
            // so the 5 s cap would never apply. A resume after cancellation is ignored.
            Dispatchers.IO.asExecutor().execute {
                val found = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(place.latitude, place.longitude, 1)?.firstOrNull()
                } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }
                continuation.resume(found)
            }
        }
    } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }
    address?.run { locality ?: subAdminArea ?: adminArea }?.takeIf(String::isNotBlank)
}

fun openDirections(context: Context, to: Place): Boolean {
    for (uri in listOf("google.navigation:q=${to.latitude},${to.longitude}", "geo:${to.latitude},${to.longitude}?q=${to.latitude},${to.longitude}(${Uri.encode(to.name)})")) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))); return true }
        catch (_: android.content.ActivityNotFoundException) { }
    }
    return false
}
