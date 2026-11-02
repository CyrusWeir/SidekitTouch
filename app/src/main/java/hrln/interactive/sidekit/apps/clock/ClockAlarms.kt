package hrln.interactive.sidekit.apps.clock

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class ClockAlarms(private val context: Context) : ClockScheduling {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun pending(id: String, at: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ClockReceiver::class.java).setData(Uri.parse("sidekit://clock/$id")).putExtra("id", id).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun reconcile(before: ClockData, after: ClockData) {
        before.alarms.filter { old -> after.alarms.none { it == old } }.forEach { alarms.cancel(pending(it.id)) }
        after.alarms.filter { it.enabled && it !in before.alarms }.forEach { schedule(it.id, it.nextAt) }
        if (before.timer != after.timer) {
            alarms.cancel(pending(ClockRepository.TIMER_ID))
            after.timer.endAt?.let { at ->
                val operation = pending(ClockRepository.TIMER_ID, at)
                val trigger = after.timer.elapsedEndAt ?: (android.os.SystemClock.elapsedRealtime() + (at - System.currentTimeMillis()).coerceAtLeast(0))
                try {
                    if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
                    else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
                } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation) }
            }
        }
        if (after.ringing == null) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
    private fun schedule(id: String, at: Long) {
        val operation = pending(id, at)
        val show = PendingIntent.getActivity(context, 0, Intent(context, RingingActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            if (alarms.canScheduleExactAlarms()) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(at, System.currentTimeMillis() + 500), show), operation)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at, System.currentTimeMillis() + 500), operation)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at, System.currentTimeMillis() + 500), operation) }
    }
    fun notify(ring: Ringing) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("clock_alarms", "Clock alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 0, Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, ClockReceiver::class.java).setAction(name).setData(Uri.parse("sidekit://clock/action/$name")).putExtra("ringAt", ring.at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, "clock_alarms")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(ring.label)
            .setContentText("Snooze for 5 minutes or dismiss").setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open).setFullScreenIntent(open, true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Snooze", action(SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "Dismiss", action(DISMISS)).build())
            .setTimeoutAfter(600000).build().apply { flags = flags or Notification.FLAG_INSISTENT }
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { /* Permission status is explained in Clock. */ }
    }
    companion object { const val NOTIFICATION_ID = 41001; const val SNOOZE = "clock.snooze"; const val DISMISS = "clock.dismiss" }
}

class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8000) {
                    val repo = Clock.repository(context)
                    repo.data.filterNotNull().first()
                    when (intent.action) {
                        ClockAlarms.SNOOZE, ClockAlarms.DISMISS -> {
                            if (repo.data.value?.ringing?.at != intent.getLongExtra("ringAt", -1)) return@withTimeout
                            if (intent.action == ClockAlarms.SNOOZE) repo.snooze() else repo.dismiss()
                            repo.awaitSaved()
                        }
                        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> { repo.timeChanged(); repo.awaitSaved() }
                        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ClockAlarms(context).reconcile(ClockData(), repo.data.value!!)
                        else -> {
                            val ring = repo.fire(intent.getStringExtra("id") ?: return@withTimeout, intent.getLongExtra("at", -1)) ?: return@withTimeout
                            repo.awaitSaved()
                            if (repo.data.value?.ringing == ring) ClockAlarms(context).notify(ring)
                        }
                    }
                }
            } catch (e: Exception) { android.util.Log.e("SidekitAlarms", "Could not handle alarm broadcast", e) }
            finally { pending.finish() }
        }
    }
}
