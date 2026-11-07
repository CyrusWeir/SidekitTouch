package hrln.interactive.sidekit.apps.apps

import android.content.*
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** Component and profile identify a launch target, including managed-profile applications. */
data class LaunchableApp(val component: ComponentName, val user: UserHandle, val label: String) {
    val key: String get() = "${user.hashCode()}:${component.flattenToString()}"
}

interface AppsSource {
    val apps: Flow<List<LaunchableApp>>
    suspend fun icon(app: LaunchableApp): Bitmap?
    fun launch(app: LaunchableApp)
    fun info(app: LaunchableApp)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppsRepository(private val context: Context) : AppsSource {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val icons = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    override val apps: Flow<List<LaunchableApp>> = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { changed() }
            override fun onPackageAdded(packageName: String, user: UserHandle) { changed() }
            override fun onPackageChanged(packageName: String, user: UserHandle) { changed() }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { changed() }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { changed() }
            private fun changed() { icons.evictAll(); trySend(Unit) }
        }
        launcher.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        trySend(Unit)
        awaitClose { launcher.unregisterCallback(callback); icons.evictAll() }
    }.conflate().mapLatest { load() }

    private suspend fun load(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val all = launcher.profiles.flatMap { user ->
            launcher.getActivityList(null, user).map { LaunchableApp(it.componentName, user, it.label.toString()) }
        }
        launchableApps(all, context.packageName).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override suspend fun icon(app: LaunchableApp): Bitmap? = withContext(Dispatchers.IO) {
        icons.get(app.key) ?: runCatching {
            val info = launcher.getActivityList(app.component.packageName, app.user).firstOrNull { it.componentName == app.component } ?: return@withContext null
            // Request the smallest density resource and rasterize at the display size; bounded cache.
            val drawable = info.getBadgedIcon(android.util.DisplayMetrics.DENSITY_LOW)
            Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, 48, 48)
                drawable.draw(Canvas(bitmap))
                icons.put(app.key, bitmap)
            }
        }.getOrNull()
    }
    override fun launch(app: LaunchableApp) = launcher.startMainActivity(app.component, app.user, null, null)
    override fun info(app: LaunchableApp) = launcher.startAppDetailsActivity(app.component, app.user, null, null)
}

fun matchesApp(label: String, query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty()) return true
    if (label.contains(text, ignoreCase = true)) return true
    if (!text.all(Char::isDigit)) return false
    val digits = label.lowercase().map { c ->
        when (c) { in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> c }
    }.joinToString("")
    return digits.contains(text)
}

data class AppsState(val apps: List<LaunchableApp> = emptyList(), val loaded: Boolean = false, val error: String? = null)
class AppsViewModel(val repository: AppsSource) : ViewModel() {
    val query = MutableStateFlow("")
    val state = combine(repository.apps.map { AppsState(it, true) }.catch { emit(AppsState(error = "Unable to list apps: ${it.localizedMessage}")) }, query) { state, query ->
        state.copy(apps = state.apps.filter { matchesApp(it.label, query) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AppsState())
}
