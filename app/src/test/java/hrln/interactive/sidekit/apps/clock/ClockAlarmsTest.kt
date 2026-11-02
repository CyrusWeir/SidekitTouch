package hrln.interactive.sidekit.apps.clock

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ClockAlarmsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `enabled alarm is registered as system alarm clock and delete cancels it`() {
        val manager = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarms = ClockAlarms(context)
        val data = ClockData(alarms = listOf(ClockAlarm(id = "test", nextAt = System.currentTimeMillis() + 60000)))
        alarms.reconcile(ClockData(), data)
        assertNotNull(manager.nextAlarmClock)
        assertEquals(data.alarms.first().nextAt, manager.nextAlarmClock.triggerTime)
        alarms.reconcile(data, ClockData())
        assertNull(manager.nextAlarmClock)
    }
    @Test fun `denied exact permission still schedules an inexact delivery`() {
        val manager = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ClockAlarms(context).reconcile(ClockData(), ClockData(timer = ClockTimer(System.currentTimeMillis() + 10000)))
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        assertNull(manager.nextAlarmClock)
    }
    @Test fun `ringing notification has full screen and snooze dismiss actions and is cancelled`() {
        val alarms = ClockAlarms(context)
        val ring = Ringing("test", "Alarm", System.currentTimeMillis())
        alarms.notify(ring)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(ClockAlarms.NOTIFICATION_ID)
        assertNotNull(notification.fullScreenIntent)
        assertEquals(listOf("Snooze", "Dismiss"), notification.actions.map { it.title.toString() })
        alarms.reconcile(ClockData(ringing = ring), ClockData())
        assertNull(shadowOf(manager).getNotification(ClockAlarms.NOTIFICATION_ID))
    }
}
