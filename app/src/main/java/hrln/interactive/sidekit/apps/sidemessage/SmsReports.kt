package hrln.interactive.sidekit.apps.sidemessage

import android.content.Context
import org.json.JSONObject

/** Per-part reports survive process death; repeated and out-of-order callbacks are harmless. */
internal class SmsReports(context: Context) {
    private val store = context.getSharedPreferences("sms_reports", Context.MODE_PRIVATE)

    fun record(token: Long, total: Int, part: Int, delivered: Boolean, ok: Boolean): Delivery = synchronized(lock) {
        require(total > 0 && part in 0 until total)
        val key = token.toString()
        val state = JSONObject(store.getString(key, null) ?: "{}")
        val phase = if (delivered) "d" else "s"
        if (!ok) state.put("failed", true)
        state.put("$phase$part", ok)
        val status = when {
            state.optBoolean("failed") -> Delivery.Failed
            (0 until total).all { state.optBoolean("d$it") } -> Delivery.Delivered
            (0 until total).all { state.optBoolean("s$it") } -> Delivery.Sent
            else -> Delivery.Sending
        }
        val edit = store.edit().putString(key, state.toString())
        // Completed reports remain briefly so duplicates cannot downgrade a final result.
        (store.all.keys + key).sortedByDescending { it.toLongOrNull() ?: 0 }.drop(100).forEach(edit::remove)
        edit.commit()
        status
    }

    private companion object { val lock = Any() }
}
