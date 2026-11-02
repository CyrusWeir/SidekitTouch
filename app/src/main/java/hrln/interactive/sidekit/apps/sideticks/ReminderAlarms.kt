package hrln.interactive.sidekit.apps.sideticks

import android.app.*
import android.content.*
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import hrln.interactive.sidekit.MainActivity

class ReminderAlarms(private val context: Context) : TickScheduling {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun pending(id: Int) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("sidekit://reminder/$id")).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun reconcile(before: List<Tick>, after: List<Tick>) {
        before.filter { old -> after.none { it == old } }.forEach { alarms.cancel(pending(it.id)) }
        before.filter { old -> after.none { it.id == old.id && !it.ticked } }.forEach {
            context.getSystemService(NotificationManager::class.java).cancel("reminder", it.id)
        }
        after.filter { !it.ticked && !it.notified && it.dueAt != null }.forEach { tick ->
            val at = maxOf(tick.dueAt!!, System.currentTimeMillis() + 1000)
            try {
                if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(tick.id))
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(tick.id))
            } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(tick.id)) }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8000) {
                    val repo = Sideticks.repository(context)
                    val ticks = repo.ticks.filterNotNull().first()
                    if (intent.action in RESTORE_ACTIONS) {
                        ReminderAlarms(context).reconcile(emptyList(), ticks)
                    } else {
                        val tick = ticks.firstOrNull { it.id == intent.getIntExtra("id", -1) } ?: return@withTimeout
                        if (tick.ticked || tick.notified || tick.dueAt == null || tick.dueAt > System.currentTimeMillis()) return@withTimeout
                        val manager = context.getSystemService(NotificationManager::class.java)
                        manager.createNotificationChannel(NotificationChannel("reminders", "Sidetick reminders", NotificationManager.IMPORTANCE_HIGH))
                        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                        try {
                            manager.notify("reminder", tick.id, Notification.Builder(context, "reminders")
                                .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(tick.title)
                                .setContentText("Sidetick due now").setContentIntent(open).setAutoCancel(true).build())
                        } catch (_: SecurityException) { /* Notification permission can be revoked after scheduling. */ }
                        repo.markNotified(tick.id)
                        repo.awaitSaved()
                    }
                }
            } catch (e: Exception) { android.util.Log.e("SidekitAlarms", "Could not handle alarm broadcast", e) }
            finally { pending.finish() }
        }
    }
    companion object {
        val RESTORE_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
