package hrln.interactive.sidekit.apps.sidemessage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import hrln.interactive.sidekit.MainActivity
import hrln.interactive.sidekit.design.icons.SidekitIcon

/** Notifications for incoming texts. Tapping one opens its conversation. */
object SidemessageNotifications {
    private const val CHANNEL = "sidemessage"
    private const val MMS_ID = 0x5D3_0001

    fun incoming(context: Context, threadId: Long, address: String, body: String) {
        val repo = Sidemessage.repository(context)
        // Already reading this conversation: the new message appears on screen instead.
        if (repo.isVisible(address)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val name = repo.names.name(address) ?: address
        val open = Intent(context, MainActivity::class.java)
            .setAction(IncomingLinks.ACTION_OPEN_THREAD)
            .putExtra(IncomingLinks.EXTRA_THREAD, threadId)
            .putExtra(IncomingLinks.EXTRA_ADDRESSES, arrayOf(address))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, idFor(threadId, address), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(SidekitIcon.Chat.drawable)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .build()
        try {
            manager.notify(idFor(threadId, address), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS refused (Android 13+).
        }
    }

    fun mmsError(context: Context, message: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(SidekitIcon.Chat.drawable)
            .setContentTitle("Multimedia message")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(MMS_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    /** Clears a conversation's notification once it's open. */
    fun cancel(context: Context, threadId: Long, addresses: List<String>) {
        val manager = NotificationManagerCompat.from(context)
        if (threadId > 0) manager.cancel(idFor(threadId, ""))
        addresses.forEach { manager.cancel(idFor(-1, it)) }
    }

    private fun idFor(threadId: Long, address: String): Int =
        if (threadId > 0) threadId.toInt() else Addresses.key(address).hashCode()

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
    }
}
