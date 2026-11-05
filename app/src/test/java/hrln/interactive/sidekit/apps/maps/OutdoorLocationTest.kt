package hrln.interactive.sidekit.apps.maps

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OutdoorLocationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = shadowOf(context.getSystemService(LocationManager::class.java))

    @Before fun setup() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        manager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        manager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Test fun deniedLocationReturnsWithoutRequestingProviders() = runTest {
        assertNull(locate(context))
        assertTrue(manager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }

    @Test fun gpsFixSucceedsWhileNetworkIsStillWaiting() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fix = async { locate(context) }
        runCurrent()
        manager.simulateLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = -33.86
            longitude = 151.21
        })
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertTrue("GPS must not wait for the network provider", fix.isCompleted)
        assertEquals(-33.86, fix.await()!!.latitude, 0.00001)
        assertTrue(manager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty())
    }

    @Test fun approximatePermissionStillAllowsNetworkLocation() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fix = async { locate(context) }
        runCurrent()
        assertTrue(manager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        manager.simulateLocation(LocationManager.NETWORK_PROVIDER, Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = -34.0
            longitude = 151.0
        })
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertTrue(fix.isCompleted)
        assertEquals(-34.0, fix.await()!!.latitude, 0.00001)
    }

    @Test fun cancellationUnregistersBothProviders() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fix = async { locate(context) }
        runCurrent()
        fix.cancelAndJoin()
        assertTrue(manager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty())
        assertTrue(manager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }
}
