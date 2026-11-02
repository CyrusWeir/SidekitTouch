package hrln.interactive.sidekit.apps.health

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId

fun canCount(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
fun stepReadings(context: Context) = callbackFlow<Long> {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    if (sensor == null || !canCount(context)) { close(); return@callbackFlow }
    val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) { trySend(event.values[0].toLong()) }
    }
    try { if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) close() }
    catch (_: SecurityException) { close() }
    awaitClose { manager.unregisterListener(listener) }
}
fun recordSteps(context: Context, repo: HealthRepository, counter: Long) {
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    repo.sample(LocalDate.now().toString(), counter, boot)
}

/** Inexact, non-wakeup alarm; never holds the device awake or starts a service. */
fun scheduleRollover(context: Context) {
    if (!canCount(context)) return
    val time = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val intent = PendingIntent.getBroadcast(context, 71, Intent(context, StepRolloverReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, time, 60 * 60_000L, intent)
}
class StepRolloverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!canCount(context)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val repo = HealthRepository.get(context)
                withTimeoutOrNull(4_000) {
                    repo.store.state.filterNotNull().first()
                    stepReadings(context).firstOrNull()?.let { recordSteps(context, repo, it); repo.store.flush() }
                }
                scheduleRollover(context)
            } finally { pending.finish() }
        }
    }
}
