package hrln.interactive.sidekit.apps.sideticks

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReminderAlarmsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `due reminders schedule without exact permission and completion cancels delivery`() {
        val manager = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val alarm = ReminderAlarms(context)
        val tick = Tick(71, "Milk", dueAt = System.currentTimeMillis() + 60000)
        alarm.reconcile(emptyList(), listOf(tick))
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        alarm.reconcile(listOf(tick), listOf(tick.copy(ticked = true)))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }
    @Test fun `restoring a snapshot schedules only undelivered open reminders`() {
        val manager = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ReminderAlarms(context).reconcile(emptyList(), listOf(Tick(1, "Due", dueAt = 1), Tick(2, "Done", true, 1), Tick(3, "Delivered", dueAt = 1, notified = true), Tick(4, "No due")))
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
    }
}
