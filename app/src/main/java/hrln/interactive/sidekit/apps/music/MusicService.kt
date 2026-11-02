package hrln.interactive.sidekit.apps.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import hrln.interactive.sidekit.MainActivity
import hrln.interactive.sidekit.design.icons.SidekitIcon

/**
 * Keeps music playing when SideKit isn't in front: a `mediaPlayback` foreground service whose
 * notification is the platform media style, tied to [MusicPlayback]'s session (so the lock
 * screen and the keypad's media keys see it too).
 *
 * It does no work of its own. Playing: foreground with an ongoing notification. Paused: drops
 * out of the foreground but leaves the notification so playback can be resumed from it. Queue
 * cleared (or the notification dismissed while paused): gone.
 */
class MusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playback = MusicPlayback.get(this)
        // Started with startForegroundService: must enter the foreground before anything else.
        // (Even when about to stop: a service started that way that never does is a crash.)
        startForeground(NOTIFICATION_ID, notification(playback, playback.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playback.togglePlayPause()
            ACTION_NEXT -> playback.next()
            ACTION_PREVIOUS -> playback.previous()
            ACTION_STOP -> playback.stop()
        }
        update(playback.state.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    private fun update(state: PlaybackState) {
        val playback = MusicPlayback.get(this)
        when {
            state.track == null -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            state.isPlaying -> startForeground(NOTIFICATION_ID, notification(playback, state), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else -> {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(playback, state))
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun notification(playback: MusicPlayback, state: PlaybackState): Notification {
        val track = state.track
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(SidekitIcon.MusicNote2.drawable)
            .setContentTitle(track?.title.orEmpty())
            .setContentText(track?.artist.orEmpty())
            .setLargeIcon(playback.notificationArt)
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP))
            .setOngoing(state.isPlaying)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, SidekitIcon.SkipPrevious.drawable), "Previous", action(ACTION_PREVIOUS)).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (state.isPlaying) SidekitIcon.Pause.drawable else SidekitIcon.PlayArrow.drawable),
                    if (state.isPlaying) "Pause" else "Play",
                    action(ACTION_PLAY_PAUSE),
                ).build(),
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, SidekitIcon.SkipNext.drawable), "Next", action(ACTION_NEXT)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(playback.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    /** Foreground-service intents: tapping Play in the notification may restart a stopped service. */
    private fun action(action: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        action.hashCode(),
        Intent(this, MusicService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL = "music"
        private const val NOTIFICATION_ID = 0x6D75 // "mu"
        private const val ACTION_PLAY_PAUSE = "hrln.interactive.sidekit.music.PLAY_PAUSE"
        private const val ACTION_NEXT = "hrln.interactive.sidekit.music.NEXT"
        private const val ACTION_PREVIOUS = "hrln.interactive.sidekit.music.PREVIOUS"
        private const val ACTION_STOP = "hrln.interactive.sidekit.music.STOP"

        private var running: MusicService? = null

        /**
         * Brings the service in line with [state]: started (foreground) when music starts, the
         * notification updated while it runs, stopped with the queue.
         */
        fun sync(context: Context, state: PlaybackState) {
            val service = running
            if (service != null) {
                service.update(state)
                return
            }
            if (!state.isPlaying) return
            try {
                context.startForegroundService(Intent(context, MusicService::class.java))
            } catch (_: IllegalStateException) {
                // Not allowed from the background (e.g. a media key with SideKit hidden on API 31+).
                // Playback still works; it just isn't protected by a foreground service.
            }
        }
    }
}
