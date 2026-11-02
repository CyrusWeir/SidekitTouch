package hrln.interactive.sidekit.apps.compass

import android.content.Context
import android.hardware.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.*

/** Circular smoothing takes the short path across 359° → 0°. */
fun smoothHeading(previous: Float, next: Float, weight: Float = .18f): Float =
    (previous + ((next - previous + 540f) % 360f - 180f) * weight + 360f) % 360f

class HeadingSensor(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val available = rotation != null || (accelerometer != null && magnetometer != null)
    // Read only inside Canvas. Sensor events invalidate drawing, never the composition.
    val angle = mutableFloatStateOf(0f)
    var latest by mutableStateOf<Float?>(null)
        private set
    val accuracy = callbackFlow {
        if (!available) { trySend(SensorManager.SENSOR_STATUS_UNRELIABLE); close(); return@callbackFlow }
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val magnetic = FloatArray(3)
        var hasGravity = false
        var hasMagnetic = false
        var started = false
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor, value: Int) {
                if (sensor.type == Sensor.TYPE_ROTATION_VECTOR || sensor.type == Sensor.TYPE_MAGNETIC_FIELD) trySend(value)
            }
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    Sensor.TYPE_ACCELEROMETER -> { event.values.copyInto(gravity, endIndex = 3); hasGravity = true }
                    Sensor.TYPE_MAGNETIC_FIELD -> { event.values.copyInto(magnetic, endIndex = 3); hasMagnetic = true }
                }
                if (rotation == null && !(hasGravity && hasMagnetic && SensorManager.getRotationMatrix(matrix, null, gravity, magnetic))) return
                SensorManager.getOrientation(matrix, orientation)
                val heading = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
                latest = heading
                angle.floatValue = if (started) smoothHeading(angle.floatValue, heading) else heading
                started = true
            }
        }
        val sensors = if (rotation != null) listOf(rotation) else listOfNotNull(accelerometer, magnetometer)
        val registered = sensors.map { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }.all { it }
        if (!registered) { manager.unregisterListener(listener); close() }
        awaitClose { manager.unregisterListener(listener) }
    }
}
