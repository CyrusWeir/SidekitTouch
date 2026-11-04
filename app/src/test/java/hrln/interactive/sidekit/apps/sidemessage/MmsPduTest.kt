package hrln.interactive.sidekit.apps.sidemessage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.mms.pdu_alt.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class MmsPduTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun outgoingPduContainsRecipientsUtf8BodyAndBinaryPicture() {
        val body = PduBody().apply {
            addPart(PduPart().apply { contentType = "text/plain".toByteArray(); charset=106; contentLocation="text.txt".toByteArray(); data="Hello 👋".toByteArray() })
            addPart(PduPart().apply { contentType = "image/jpeg".toByteArray(); contentLocation="photo.jpg".toByteArray(); contentId="photo".toByteArray(); data=byteArrayOf(-1,-40,-1,-39) })
        }
        val request = SendReq().apply { setTo(arrayOf(EncodedStringValue("+61412345678"))); setFrom(EncodedStringValue("insert-address-token")); setBody(body); date=1_793_750_000 }
        val bytes = PduComposer(context,request).make()
        assertNotNull(bytes)
        val parsed = PduParser(bytes).parse() as SendReq
        assertEquals("+61412345678",parsed.to.single().string)
        assertEquals("Hello 👋",String(parsed.body.getPart(0).data,Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(-1,-40,-1,-39),parsed.body.getPart(1).data)
    }
    @Test fun notificationAndRetrievePdusAreParsedUsingTheWireFormat() {
        val notification = pdu {
            bytes(0x8c,0x82,0x98); text("transaction")
            bytes(0x8d,0x92,0x8a,0x80,0x8e,1,50,0x88,3,0x81,1,60,0x83); text("http://mmsc.example.test/message")
        }
        val push = PduParser(notification).parse() as NotificationInd
        assertEquals("http://mmsc.example.test/message",String(push.contentLocation))
        val retrieve = pdu {
            bytes(0x8c,0x84,0x8d,0x92,0x85,4,0x6a,0xf0,0x00,0x00,0x99,0x80,0x84,0xa3)
            bytes(1,1,5,0x83); write("Hello".toByteArray())
        }
        val downloaded = PduParser(retrieve).parse() as RetrieveConf
        assertEquals(PduHeaders.RETRIEVE_STATUS_OK, downloaded.retrieveStatus)
        assertEquals("Hello", String(downloaded.body.getPart(0).data))
    }
    @Test fun malformedOrOversizeMmsIsRejected() {
        assertNull(PduParser(byteArrayOf(0,1,2)).parse())
        try { MmsTransport.readBounded(byteArrayOf(1,2,3).inputStream(),2); fail() } catch (_: java.io.IOException) {}
    }
    @Test fun platformMmsFileGrantIsLimitedToThePduDirectory() {
        val file = java.io.File(MmsTransport.directory(context),"test.download").apply { writeBytes(byteArrayOf(1,2,3)) }
        val uri = MmsTransport.uri(context,file)
        assertEquals("${context.packageName}.mms", uri.authority)
        assertArrayEquals(byteArrayOf(1,2,3),context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        try { MmsTransport.uri(context,java.io.File(context.noBackupFilesDir,"mail_account.sealed")); fail("Granted credentials") }
        catch (_: IllegalArgumentException) {}
    }

    private fun pdu(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    private fun ByteArrayOutputStream.bytes(vararg b: Int) { b.forEach(::write) }
    private fun ByteArrayOutputStream.text(value: String) { write(value.toByteArray()); write(0) }
}
