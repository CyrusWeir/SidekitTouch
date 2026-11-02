package hrln.interactive.sidekit.apps.media

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map

/**
 * Emits once on collection and again whenever MediaStore content under [uris] changes (a photo
 * taken or deleted, a song copied in). MediaProvider notifies once per row, so bursts are
 * debounced into one reload.
 *
 * The observer exists only while the flow is collected: a screen collecting it through
 * `WhileSubscribed` + `collectAsStateWithLifecycle` watches the store only while it is visible.
 */
@OptIn(FlowPreview::class)
fun ContentResolver.changes(vararg uris: Uri, debounceMs: Long = 400): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(false)
        }
    }
    uris.forEach { registerContentObserver(it, true, observer) }
    send(true)
    awaitClose { unregisterContentObserver(observer) }
}.debounce { initial -> if (initial) 0L else debounceMs }.map { }

/** "3:07", "1:02:45". */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
