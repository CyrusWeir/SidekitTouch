package hrln.interactive.sidekit.apps.compass

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.apps.health.stepReadings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SensorLifetimeTest {
    @Test fun compassUnregistersWhenVisibleCollectionStops() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = shadowOf(context.getSystemService(SensorManager::class.java))
        manager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR))
        val sensor = HeadingSensor(context)
        assertTrue(sensor.available)
        assertTrue(manager.listeners.isEmpty())
        val job = launch { sensor.accuracy.collect {} }
        runCurrent()
        assertEquals(1, manager.listeners.size)
        job.cancelAndJoin()
        assertTrue(manager.listeners.isEmpty())
    }
    @Test fun fallbackUsesBothSensorsAndUnregistersBoth() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val real = context.getSystemService(SensorManager::class.java)
        val manager = shadowOf(real)
        real.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let(manager::removeSensor)
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        val magnetic = ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD)
        manager.addSensor(accelerometer); manager.addSensor(magnetic)
        val sensor = HeadingSensor(context)
        val job = launch { sensor.accuracy.collect {} }; runCurrent()
        val listener = manager.listeners.single()
        assertTrue(manager.hasListener(listener, accelerometer))
        assertTrue(manager.hasListener(listener, magnetic))
        job.cancelAndJoin()
        assertTrue(manager.listeners.isEmpty())
    }
    @Test fun stepCounterUnregistersAndDenialRegistersNothing() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val manager = shadowOf(context.getSystemService(SensorManager::class.java))
        manager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER))
        shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val denied = launch { stepReadings(context).collect {} }; runCurrent()
        assertTrue(manager.listeners.isEmpty()); denied.join()
        shadowOf(context).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val allowed = launch { stepReadings(context).collect {} }; runCurrent()
        assertEquals(1, manager.listeners.size)
        allowed.cancelAndJoin()
        assertTrue(manager.listeners.isEmpty())
    }
}
