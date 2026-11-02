package hrln.interactive.sidekit.apps.sidemessage

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.launch

/** Sends SMS and owns the provider rows the default SMS app must write itself. */
interface SmsTransport {
    /** Whether Sidekit holds the SMS role (and so writes messages to the provider itself). */
    val isDefault: Boolean

    /** Writes an outgoing message to the provider as "sending" (default app only). Returns its row id. */
    fun insertOutgoing(address: String, text: String, date: Long, threadId: Long): Long?

    /** Hands [text] to the radio. [token] and [rowId] come back in the sent/delivered reports. */
    fun send(address: String, text: String, token: Long, rowId: Long): Boolean

    /** Updates a row this app wrote (default app only). */
    fun markRow(rowId: Long, delivery: Delivery)

    fun deleteRow(rowId: Long)
}

class AndroidSmsTransport(private val context: Context) : SmsTransport {
    private val resolver = context.contentResolver

    override val isDefault: Boolean get() = SmsRole.isDefault(context)

    override fun insertOutgoing(address: String, text: String, date: Long, threadId: Long): Long? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            // Without a thread id the provider files the row under the address's own thread.
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }
        return try {
            resolver.insert(Telephony.Sms.CONTENT_URI, values)?.let(ContentUris::parseId)
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun send(address: String, text: String, token: Long, rowId: Long): Boolean {
        val sms = smsManager() ?: return false
        return try {
            val parts = sms.divideMessage(text)
            val sent = ArrayList(parts.indices.map { statusIntent(ACTION_SENT, token, rowId, parts.size, it, mutable = false) })
            // Delivery reports carry the status PDU as an extra the system fills in: must be mutable.
            val delivered = ArrayList(parts.indices.map { statusIntent(ACTION_DELIVERED, token, rowId, parts.size, it, mutable = true) })
            if (parts.size <= 1) {
                sms.sendTextMessage(address, null, text, sent.first(), delivered.first())
            } else {
                sms.sendMultipartTextMessage(address, null, parts, sent, delivered)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun markRow(rowId: Long, delivery: Delivery) {
        if (rowId < 0) return
        val values = ContentValues()
        when (delivery) {
            Delivery.Sending -> values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            Delivery.Sent -> values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            Delivery.Failed -> values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            Delivery.Delivered -> {
                values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            }
            Delivery.None -> return
        }
        try {
            resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId), values, null, null)
        } catch (_: RuntimeException) {
        }
    }

    override fun deleteRow(rowId: Long) {
        try {
            resolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId), null, null)
        } catch (_: RuntimeException) {
        }
    }

    private fun smsManager(): SmsManager? {
        val manager = context.getSystemService(SmsManager::class.java) ?: return null
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) manager.createForSubscriptionId(sub) else manager
    }

    private fun statusIntent(action: String, token: Long, rowId: Long, parts: Int, part: Int, mutable: Boolean): PendingIntent {
        // A distinct data URI per message keeps each PendingIntent separate (request code alone wouldn't).
        val intent = Intent(action, Uri.parse("sidekit-sms://status/$token/$part"), context, SmsStatusReceiver::class.java)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_ROW, rowId)
            .putExtra(EXTRA_PARTS, parts)
            .putExtra(EXTRA_PART, part)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    companion object {
        const val ACTION_SENT = "hrln.interactive.sidekit.sms.SENT"
        const val ACTION_DELIVERED = "hrln.interactive.sidekit.sms.DELIVERED"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ROW = "row"
        const val EXTRA_PARTS = "parts"
        const val EXTRA_PART = "part"
    }
}

/** The default-SMS-app role (Android 10+: [RoleManager.ROLE_SMS]). */
object SmsRole {
    fun isDefault(context: Context): Boolean = try {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } catch (_: RuntimeException) {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    fun isAvailable(context: Context): Boolean = try {
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_SMS) == true
    } catch (_: RuntimeException) {
        false
    }

    fun requestIntent(context: Context): Intent? = try {
        context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_SMS)
    } catch (_: RuntimeException) {
        null
    }
}

/** Sent and delivered reports for messages Sidemessage sent. Not exported: only our PendingIntents reach it. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getLongExtra(AndroidSmsTransport.EXTRA_TOKEN, -1L)
        val row = intent.getLongExtra(AndroidSmsTransport.EXTRA_ROW, -1L)
        val parts = intent.getIntExtra(AndroidSmsTransport.EXTRA_PARTS, 1)
        val repo = Sidemessage.repository(context)
        val result = resultCode
        val pending = goAsync()
        repo.scope.launch(repo.io) {
            try {
                val delivered = intent.action == AndroidSmsTransport.ACTION_DELIVERED
                val ok = when (intent.action) {
                    AndroidSmsTransport.ACTION_SENT -> result == Activity.RESULT_OK
                    AndroidSmsTransport.ACTION_DELIVERED -> deliveryOk(intent)
                    else -> null
                }
                if (ok != null) {
                    val status = SmsReports(context).record(token, parts,
                        intent.getIntExtra(AndroidSmsTransport.EXTRA_PART, 0), delivered, ok)
                    repo.report(token, row, status)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** True delivered, false failed, null still pending (a temporary status). */
    private fun deliveryOk(intent: Intent): Boolean? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format") ?: "3gpp"
        val status = SmsMessage.createFromPdu(pdu, format)?.status ?: return null
        if (format == "3gpp2") return if (status == 0) true else null
        // TP-Status (3GPP 23.040): 0x00-0x1F completed, 0x20-0x3F still trying, 0x40+ failed.
        return when {
            status < 0x20 -> true
            status >= 0x40 -> false
            else -> null
        }
    }
}

/**
 * SMS_DELIVER: only the default SMS app gets this, and it must store the message itself. Stores it
 * in the inbox and posts a notification.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val app = context.applicationContext
        val repo = Sidemessage.repository(app)
        val pending = goAsync()
        repo.scope.launch(repo.io) {
            try {
                IncomingSms.store(app, intent)?.let { (threadId, address, body) ->
                    SidemessageNotifications.incoming(app, threadId, address, body)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

object IncomingSms {
    /** Writes the message to the inbox. Returns (thread id, sender, text), or null if nothing was stored. */
    fun store(context: Context, intent: Intent): Triple<Long, String, String>? {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        val first = parts.firstOrNull() ?: return null
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return null
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.PROTOCOL, first.protocolIdentifier)
            put(Telephony.Sms.REPLY_PATH_PRESENT, if (first.isReplyPathPresent) 1 else 0)
            first.serviceCenterAddress?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
            val sub = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) put(Telephony.Sms.SUBSCRIPTION_ID, sub)
        }
        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val threadId = try {
            resolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } catch (_: RuntimeException) {
            -1L
        }
        return Triple(threadId, address, body)
    }
}

/**
 * WAP_PUSH_DELIVER (an MMS has arrived). Required for the SMS role. Downloading and decoding MMS
 * (carrier MMSC over the MMS APN, PDU parsing) isn't implemented, so this only tells the user.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        SidemessageNotifications.unsupportedMms(context.applicationContext)
    }
}

/** "Respond via message" from the incoming-call screen. Required for the SMS role. */
class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val addresses = intent?.data?.let(Addresses::fromUri).orEmpty()
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE && text.isNotEmpty() && addresses.isNotEmpty()) {
            val repo = Sidemessage.repository(applicationContext)
            repo.scope.launch(repo.io) {
                try { repo.send(addresses, text, threadId = -1L)?.join() }
                finally { stopSelf(startId) }
            }
        } else stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
