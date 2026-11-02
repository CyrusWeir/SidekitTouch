package hrln.interactive.sidekit.apps.sidemessage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SmsPlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun multipartReportsSurviveRecreationAndRequireEveryPart() {
        val reports = SmsReports(context)
        assertEquals(Delivery.Sending, reports.record(100, 2, 0, false, true))
        assertEquals(Delivery.Sending, reports.record(100, 2, 0, false, true))
        assertEquals(Delivery.Sent, SmsReports(context).record(100, 2, 1, false, true))
        assertEquals(Delivery.Sent, reports.record(100, 2, 0, true, true))
        assertEquals(Delivery.Delivered, reports.record(100, 2, 1, true, true))
        assertEquals(Delivery.Delivered, reports.record(100, 2, 0, false, true))
    }

    @Test fun aFailedPartRemainsFailedAfterOtherPartsSucceed() {
        val reports = SmsReports(context)
        assertEquals(Delivery.Failed, reports.record(200, 2, 0, false, false))
        assertEquals(Delivery.Failed, SmsReports(context).record(200, 2, 1, false, true))
        assertEquals(Delivery.Failed, reports.record(200, 2, 0, true, true))
    }

    @Test fun pinsRoundTripThroughTheAtomicFile() {
        val file = File(context.cacheDir, "pins.txt")
        FilePinStore(file).write(listOf("+61412345678", "0411111111"))
        assertEquals(listOf("+61412345678", "0411111111"), FilePinStore(file).read())
        FilePinStore(file).write(emptyList())
        assertTrue(FilePinStore(file).read().isEmpty())
    }

    @Test fun smsLinksDecodeRecipientsAndOnlyTheBodyParameter() {
        val route = IncomingLinks.routeFor(Intent(Intent.ACTION_SENDTO,
            Uri.parse("smsto:%2B61412345678?other=1&body=Hello%20%26%20goodbye")))
        assertEquals(SidemessageRoutes.conversation(listOf("+61412345678"), body = "Hello & goodbye"), route)
        assertNull(IncomingLinks.routeFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))))
    }
}
