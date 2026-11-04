package hrln.interactive.sidekit.apps.sideport.bridge

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BridgeMessage(val sender: String?, val text: String, val time: Long, val mine: Boolean = false)

/** A ported app's conversation (or plain notification), read from its notifications. */
data class Conversation(
    val key: String,
    val packageName: String,
    val title: String,
    val messages: List<BridgeMessage>,
    /** The notification's inline reply, while the notification is still showing. */
    val reply: Notification.Action? = null,
    val open: PendingIntent? = null,
    val updated: Long = 0L,
)

/**
 * The fast path for ported apps: messages and media read straight from Android instead of from
 * the app's screen. Messaging apps post their chats as notifications with an inline reply, and
 * players publish a media session, so both work without opening the app at all.
 */
object Bridge {
    private const val MAX_MESSAGES = 50

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    private val _media = MutableStateFlow<Map<String, MediaController>>(emptyMap())
    val media: StateFlow<Map<String, MediaController>> = _media.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun conversationsFor(packageName: String) =
        _conversations.value.values.filter { it.packageName == packageName }.sortedByDescending { it.updated }

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(context, Listener::class.java)
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == me }
    }

    internal fun post(context: Context, sbn: StatusBarNotification) {
        if (!PortedApps.get(context).contains(sbn.packageName)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val title = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val incoming = style?.messages?.mapNotNull { m ->
            m.text?.toString()?.let { BridgeMessage(m.person?.name?.toString(), it, m.timestamp) }
        } ?: listOfNotNull(
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.let { BridgeMessage(null, it, sbn.postTime) },
        )
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val key = "${sbn.packageName}|${sbn.tag}|${sbn.id}|$title"
        _conversations.update { all ->
            val old = all[key]
            // Keep what we've seen (and our own replies), add what's new.
            val merged = ((old?.messages ?: emptyList()) + incoming)
                .distinctBy { Triple(it.sender, it.text, it.time) }
                .sortedBy { it.time }
                .takeLast(MAX_MESSAGES)
            all + (key to Conversation(key, sbn.packageName, title, merged, reply, n.contentIntent, sbn.postTime))
        }
    }

    internal fun removed(sbn: StatusBarNotification) {
        _conversations.update { all ->
            all.mapValues { (_, c) -> if (c.key.startsWith("${sbn.packageName}|${sbn.tag}|${sbn.id}|")) c.copy(reply = null) else c }
        }
    }

    /** Sends [text] through the conversation's inline reply. False if it can no longer be replied to. */
    fun reply(context: Context, conversationKey: String, text: String): Boolean {
        val c = _conversations.value[conversationKey] ?: return false
        val action = c.reply ?: return false
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (inputs.isEmpty()) return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        return try {
            action.actionIntent.send(context, 0, intent)
            _conversations.update { all ->
                all + (c.key to c.copy(messages = c.messages + BridgeMessage("You", text, System.currentTimeMillis(), mine = true), updated = System.currentTimeMillis()))
            }
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    internal fun setMedia(context: Context, controllers: List<MediaController>?) {
        val ported = PortedApps.get(context)
        _media.value = controllers.orEmpty().filter { ported.contains(it.packageName) }.associateBy { it.packageName }
    }

    internal fun setConnected(on: Boolean) {
        _connected.value = on
        if (!on) _media.value = emptyMap()
    }

    /** Notification access for Sideport; also what lets it see other apps' media sessions. */
    class Listener : NotificationListenerService() {
        private var sessions: MediaSessionManager? = null
        private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { setMedia(this, it) }

        override fun onListenerConnected() {
            setConnected(true)
            runCatching { activeNotifications }.getOrNull()?.forEach { post(this, it) }
            sessions = getSystemService(MediaSessionManager::class.java)?.also { manager ->
                val me = ComponentName(this, Listener::class.java)
                runCatching {
                    manager.addOnActiveSessionsChangedListener(sessionsChanged, me)
                    setMedia(this, manager.getActiveSessions(me))
                }
            }
        }

        override fun onListenerDisconnected() {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
            sessions = null
            setConnected(false)
        }

        override fun onNotificationPosted(sbn: StatusBarNotification) = post(this, sbn)

        override fun onNotificationRemoved(sbn: StatusBarNotification) = removed(sbn)
    }
}
