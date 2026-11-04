package hrln.interactive.sidekit.apps.sidemessage

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import androidx.work.*
import com.google.android.mms.pdu_alt.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Platform MMS networking selects the carrier APN and MMSC; Sidekit owns PDUs and provider rows. */
object MmsTransport {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val DOWNLOAD = "hrln.interactive.sidekit.MMS_DOWNLOADED"
    const val SENT = "hrln.interactive.sidekit.MMS_SENT"
    const val RETRY = "hrln.interactive.sidekit.MMS_RETRY"
    fun manager(context: Context, subId: Int): SmsManager {
        val manager = context.getSystemService(SmsManager::class.java)
        val id = if (SubscriptionManager.isValidSubscriptionId(subId)) subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        require(SubscriptionManager.isValidSubscriptionId(id)) { "Select a default SMS SIM in Android settings" }
        return manager.createForSubscriptionId(id)
    }
    fun directory(context: Context) = File(context.noBackupFilesDir, "mms-pdu").apply { mkdirs() }
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
    fun enqueue(context: Context, push: ByteArray, subId: Int) {
        require(push.size <= 100_000) { "MMS notification too large" }
        val key = MessageDigest.getInstance("SHA-256").digest(push).joinToString("") { "%02x".format(it) }
        File(directory(context), "$key.push").writeBytes(push)
        val work = OneTimeWorkRequestBuilder<MmsDownloadWorker>().setInputData(workDataOf("key" to key, "sub" to subId)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("mms-$key", ExistingWorkPolicy.KEEP, work)
    }
    fun retry(context: Context, row: Uri) {
        require(row.toString().matches(Regex("content://mms/[0-9]+")))
        val sub = context.contentResolver.query(row, arrayOf("sub_id"), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else -1 } ?: -1
        val key = MessageDigest.getInstance("SHA-256").digest(row.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val work = OneTimeWorkRequestBuilder<MmsDownloadWorker>().setInputData(workDataOf("key" to key, "sub" to sub, "row" to row.toString())).build()
        WorkManager.getInstance(context).enqueueUniqueWork("mms-$key", ExistingWorkPolicy.KEEP, work)
    }
    suspend fun resend(context: Context, row: Uri) = withContext(Dispatchers.IO) {
        check(SmsRole.isDefault(context)) { "Make Sidekit the default SMS app first" }
        require(row.toString().matches(Regex("content://mms/[0-9]+")))
        val sub = context.contentResolver.query(row, arrayOf("sub_id"), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else -1 } ?: -1
        val request = PduPersister.getPduPersister(context).load(row) as? SendReq ?: throw IOException("Outgoing MMS is unavailable")
        val bytes = PduComposer(context, request).make() ?: throw IOException("Cannot encode MMS")
        val key = UUID.randomUUID().toString()
        val file = File(directory(context), "$key.send").apply { writeBytes(bytes) }
        val sms = manager(context, sub)
        mark(context, row, Telephony.Mms.MESSAGE_BOX_OUTBOX)
        try { sms.sendMultimediaMessage(context, uri(context, file), null, null, callback(context, SENT, key, row, sub)) }
        catch (e: Exception) { mark(context, row, Telephony.Mms.MESSAGE_BOX_FAILED); file.delete(); throw e }
    }
    fun callback(context: Context, action: String, key: String, row: Uri, sub: Int): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, MmsResultReceiver::class.java).setAction(action).setData(Uri.parse("sidekit-mms://$key"))
            .putExtra("key", key).putExtra("row", row.toString()).putExtra("sub", sub),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    suspend fun send(context: Context, addresses: List<String>, text: String, attachment: Uri): Uri = withContext(Dispatchers.IO) {
        check(SmsRole.isDefault(context)) { "Make Sidekit the default SMS app first" }
        require(addresses.isNotEmpty()) { "Choose a recipient" }
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        val sms = manager(context, sub)
        val mime = context.contentResolver.getType(attachment).orEmpty()
        require(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) { "Select an image, video or audio file" }
        val maxSize = sms.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300_000).coerceIn(50_000, MAX_BYTES)
        val bytes = context.contentResolver.openInputStream(attachment)?.use { readBounded(it, maxSize - 4096) } ?: throw IOException("Cannot read attachment")
        val body = PduBody()
        if (text.isNotBlank()) body.addPart(PduPart().apply {
            contentType = "text/plain".toByteArray(); charset = 106; contentLocation = "text.txt".toByteArray(); data = text.toByteArray()
        })
        body.addPart(PduPart().apply { contentType = mime.toByteArray(); contentLocation = "attachment".toByteArray(); contentId = "attachment".toByteArray(); data = bytes })
        val request = SendReq().apply {
            setTo(addresses.map { EncodedStringValue(it) }.toTypedArray()); setFrom(EncodedStringValue("insert-address-token"))
            date = System.currentTimeMillis() / 1000; setBody(body); messageSize = bytes.size.toLong() + text.toByteArray().size
        }
        val pdu = PduComposer(context, request).make() ?: throw IOException("Could not encode MMS")
        require(pdu.size <= maxSize) { "Attachment exceeds your carrier's MMS size limit. Choose a smaller file." }
        val row = PduPersister.getPduPersister(context).persist(request, Telephony.Mms.Outbox.CONTENT_URI, true, true, null, sub)
        val key = UUID.randomUUID().toString()
        val file = File(directory(context), "$key.send").apply { writeBytes(pdu) }
        try { sms.sendMultimediaMessage(context, uri(context, file), null, null, callback(context, SENT, key, row, sub)) }
        catch (e: Exception) { mark(context, row, Telephony.Mms.MESSAGE_BOX_FAILED); file.delete(); throw e }
        row
    }
    fun mark(context: Context, row: Uri, box: Int) = context.contentResolver.update(row, ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null)
    fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > limit) throw IOException("MMS exceeds size limit"); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}

class MmsDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val key = inputData.getString("key") ?: return@withContext Result.failure()
        val file = File(MmsTransport.directory(context), "$key.push")
        if ((!file.exists() && inputData.getString("row") == null) || !SmsRole.isDefault(context)) return@withContext Result.failure()
        try {
            val notification = (inputData.getString("row")?.let { PduPersister.getPduPersister(context).load(Uri.parse(it)) } ?: PduParser(file.readBytes()).parse()) as? NotificationInd ?: return@withContext Result.success()
            val requestedSub = inputData.getInt("sub", -1)
            val sub = if (SubscriptionManager.isValidSubscriptionId(requestedSub)) requestedSub else SubscriptionManager.getDefaultSmsSubscriptionId()
            val sms = MmsTransport.manager(context, sub)
            val location = String(notification.contentLocation, Charsets.ISO_8859_1)
            require(Uri.parse(location).scheme in listOf("http", "https")) { "Invalid MMS location" }
            val resolver = context.contentResolver
            val existing = resolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "m_type"), "ct_l=?", arrayOf(location), null)?.use {
                if (it.moveToFirst()) ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, it.getLong(0)) to it.getInt(1) else null
            }
            if (existing != null && existing.second != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) { file.delete(); return@withContext Result.success() }
            val row = existing?.first ?: PduPersister.getPduPersister(context).persist(notification, Telephony.Mms.Inbox.CONTENT_URI, true, true, null, sub)
            val target = File(MmsTransport.directory(context), "$key.download")
            if (target.exists() && System.currentTimeMillis() - target.lastModified() < 120_000) return@withContext Result.success()
            target.writeBytes(byteArrayOf())
            val config = sms.carrierConfigValues
            val downloadLocation = if (config.getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID, false) && location.endsWith("=")) location + String(notification.transactionId, Charsets.ISO_8859_1) else location
            sms.downloadMultimediaMessage(context, downloadLocation, MmsTransport.uri(context, target), null, MmsTransport.callback(context, MmsTransport.DOWNLOAD, key, row, sub))
            Result.success()
        } catch (_: Exception) {
            SidemessageNotifications.mmsError(context, "MMS download could not start. Check your SIM and mobile data; tap the message to retry.")
            Result.failure()
        }
    }
}

class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        val pending = goAsync()
        val repo = Sidemessage.repository(context)
        repo.scope.launchOnIo {
            try {
                val row = intent.getStringExtra("row")?.let(Uri::parse) ?: return@launchOnIo
                if (intent.action == MmsTransport.RETRY) { MmsTransport.retry(context, row); return@launchOnIo }
                val key = intent.getStringExtra("key") ?: return@launchOnIo
                // Never accept arbitrary paths from callback extras.
                require(key.matches(Regex("[a-fA-F0-9-]{32,64}")))
                if (intent.action == "hrln.interactive.sidekit.MMS_ACK") { File(MmsTransport.directory(context), "$key.ack").delete(); return@launchOnIo }
                val sent = intent.action == MmsTransport.SENT
                val file = File(MmsTransport.directory(context), "$key.${if (sent) "send" else "download"}")
                if (sent) {
                    val confirmed = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let { PduParser(it).parse() as? SendConf }
                    val ok = code == Activity.RESULT_OK && (confirmed == null || confirmed.responseStatus == PduHeaders.RESPONSE_STATUS_OK)
                    MmsTransport.mark(context, row, if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
                    if (confirmed?.messageId != null) context.contentResolver.update(row, ContentValues().apply { put("m_id", PduPersister.toIsoString(confirmed.messageId)) }, null, null)
                    if (!ok) SidemessageNotifications.mmsError(context, "MMS was not confirmed sent. Check the conversation before retrying.")
                    file.delete()
                } else if (intent.action == MmsTransport.DOWNLOAD) {
                    if (code != Activity.RESULT_OK) { SidemessageNotifications.mmsError(context, "MMS download failed. Check mobile data and tap the message to retry."); file.delete(); return@launchOnIo }
                    val pdu = file.inputStream().use { PduParser(MmsTransport.readBounded(it, MmsTransport.MAX_BYTES)).parse() } as? RetrieveConf ?: throw IOException("Invalid MMS PDU")
                    require(pdu.retrieveStatus == 0 || pdu.retrieveStatus == PduHeaders.RETRIEVE_STATUS_OK)
                    val type = context.contentResolver.query(row, arrayOf("m_type"), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else null }
                    if (type != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) { file.delete(); return@launchOnIo }
                    val notification = PduPersister.getPduPersister(context).load(row) as? NotificationInd
                    // Replace the notification in place, so repeated callbacks cannot create duplicate messages.
                    val result = PduPersister.getPduPersister(context).persist(pdu, row, true, true, null, intent.getIntExtra("sub", -1))
                    val resolver = context.contentResolver
                    resolver.update(result, ContentValues().apply { put("read",0); put("seen",0) }, null, null)
                    val thread = resolver.query(result, arrayOf("thread_id"), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1 } ?: -1
                    SidemessageNotifications.incoming(context, thread, pdu.from?.string.orEmpty(), "Multimedia message")
                    file.delete(); File(MmsTransport.directory(context), "$key.push").delete()
                    notification?.let {
                        runCatching {
                            val response = NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, it.transactionId, PduHeaders.STATUS_RETRIEVED)
                            val ack = File(MmsTransport.directory(context), "$key.ack").apply { writeBytes(PduComposer(context, response).make()) }
                            MmsTransport.manager(context, intent.getIntExtra("sub", -1)).sendMultimediaMessage(context, MmsTransport.uri(context, ack), null, null,
                                MmsTransport.callback(context, "hrln.interactive.sidekit.MMS_ACK", key, row, intent.getIntExtra("sub", -1)))
                        }
                    }
                }
            } catch (_: Exception) { SidemessageNotifications.mmsError(context, "MMS could not be processed. Tap the pending message to retry.") }
            finally { pending.finish() }
        }
    }
}
private fun kotlinx.coroutines.CoroutineScope.launchOnIo(block: suspend () -> Unit) = this.launch(Dispatchers.IO) { block() }
