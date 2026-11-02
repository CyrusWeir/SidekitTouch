package hrln.interactive.sidekit.apps.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import hrln.interactive.sidekit.apps.media.AudioFocus
import hrln.interactive.sidekit.apps.media.MediaPlayerCore
import hrln.interactive.sidekit.apps.media.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the UI and the notification show. Position is an anchor (position at [anchorAt], elapsed
 * realtime) so nothing has to publish it every second: [positionAt] extrapolates while playing.
 */
@Immutable
data class PlaybackState(
    val queue: PlayQueue = PlayQueue(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorAt: Long = 0,
    val durationMs: Long = 0,
) {
    val track: Track? get() = queue.current

    fun positionAt(now: Long): Long {
        val p = if (isPlaying) positionMs + (now - anchorAt) else positionMs
        val d = durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L
        return if (d > 0) p.coerceIn(0, d) else p.coerceAtLeast(0)
    }
}

/** Playback as the Music screens see it; [MusicPlayback] in the app, a fake in tests. */
interface MusicPlayer {
    val state: StateFlow<PlaybackState>
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun setShuffle(on: Boolean)
    fun cycleRepeat()
    fun jumpTo(playPosition: Int)
}

/**
 * The one music player in the process: a [MediaPlayerCore] fed from a [PlayQueue], published as
 * a platform [MediaSession] so the media notification, the lock screen and the keypad's media keys
 * (`KEY_PLAYPAUSE`, `KEY_NEXTSONG`, `KEY_PREVIOUSSONG` → `KEYCODE_MEDIA_*`) all drive it.
 *
 * Platform MediaSession rather than media3-session: one class and a few callbacks here, against
 * ~1 MB of session/ui libraries plus ExoPlayer. MediaPlayer itself is argued for in
 * [MediaPlayerCore].
 *
 * Lives as long as the process (SideKit is the home app, so usually always). While playing,
 * [MusicService] keeps it in the foreground. Resources are held only while there's a reason:
 * the native player is released after [IDLE_RELEASE_MS] paused and reopened where it left off;
 * the session is released when the queue is cleared.
 *
 * Main thread only.
 */
class MusicPlayback private constructor(private val context: Context) : MusicPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focus = AudioFocus(context, attributes, onPause = { pause() }, onResume = { play() })
    private var core: MediaPlayerCore? = null
    private var loadedTrackId: Long? = null
    private var session: MediaSession? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null
    private var errors = 0
    private val releaseIdle = Runnable { releasePlayer() }

    /** Shuffle and repeat carry over to the next thing the user plays. */
    private var shufflePreference = false
    private var repeatPreference = RepeatMode.Off

    private val noisy = object : BroadcastReceiver() {
        // Headphones unplugged: pause rather than play out loud.
        override fun onReceive(context: Context, intent: Intent) = pause()
    }
    private var noisyRegistered = false

    val sessionToken: MediaSession.Token? get() = session?.sessionToken

    /** Album art for the notification, loaded small. */
    val notificationArt: Bitmap? get() = art

    override fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean?) {
        if (tracks.isEmpty()) return
        val queue = PlayQueue.of(tracks, startIndex, shuffle ?: shufflePreference, repeatPreference)
        errors = 0
        load(queue, autoplay = true)
    }

    override fun togglePlayPause() = if (_state.value.isPlaying) pause() else play()

    fun play() {
        val s = _state.value
        if (s.queue.isEmpty) return
        if (!focus.request()) return
        main.removeCallbacks(releaseIdle)
        val c = core
        if (c == null || loadedTrackId != s.track?.id) {
            load(s.queue, autoplay = true, startMs = s.positionMs)
            return
        }
        c.play()
        publish(s.copy(isPlaying = true, positionMs = c.positionMs, anchorAt = now()))
    }

    fun pause() {
        val s = _state.value
        if (!s.isPlaying) return
        val position = s.positionAt(now())
        core?.pause()
        publish(s.copy(isPlaying = false, positionMs = position, anchorAt = now()))
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
    }

    /** Ends playback and drops the queue: notification and session go away. */
    fun stop() {
        main.removeCallbacks(releaseIdle)
        releasePlayer()
        focus.abandon()
        artJob?.cancel()
        art = null
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        publish(PlaybackState())
    }

    override fun next() {
        val s = _state.value
        if (s.queue.isEmpty) return
        load(s.queue.skip(1), autoplay = s.isPlaying || core == null)
    }

    /** Restarts the track if it's past the first few seconds, else goes to the previous one. */
    override fun previous() {
        val s = _state.value
        if (s.queue.isEmpty) return
        if (s.positionAt(now()) > RESTART_THRESHOLD_MS) seekTo(0) else load(s.queue.skip(-1), autoplay = s.isPlaying || core == null)
    }

    override fun seekTo(ms: Long) {
        val s = _state.value
        if (s.queue.isEmpty) return
        val target = ms.coerceIn(0, (s.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
        core?.seekTo(target)
        publish(s.copy(positionMs = target, anchorAt = now()))
    }

    override fun setShuffle(on: Boolean) {
        shufflePreference = on
        val s = _state.value
        publish(s.copy(queue = s.queue.withShuffle(on)))
    }

    override fun cycleRepeat() {
        repeatPreference = repeatPreference.next()
        val s = _state.value
        publish(s.copy(queue = s.queue.withRepeat(repeatPreference)))
    }

    override fun jumpTo(playPosition: Int) {
        val s = _state.value
        load(s.queue.jumpTo(playPosition), autoplay = true)
    }

    private fun load(queue: PlayQueue, autoplay: Boolean, startMs: Long = 0) {
        val track = queue.current ?: return stop()
        val play = autoplay && focus.request()
        val c = core ?: MediaPlayerCore(context, attributes, keepCpuAwake = true).also {
            it.listener = listener
            core = it
        }
        main.removeCallbacks(releaseIdle)
        if (loadedTrackId == track.id && c.isPrepared) {
            // Same file (repeat one, restart, resume): no need to reopen it.
            c.seekTo(startMs)
            if (play) c.play() else c.pause()
        } else {
            loadedTrackId = track.id
            c.open(track.uri, startMs, play)
        }
        if (!play) main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
        val trackChanged = _state.value.track?.id != track.id
        publish(PlaybackState(queue, isPlaying = play, positionMs = startMs, anchorAt = now(), durationMs = track.durationMs))
        if (trackChanged || art == null) loadArt(track)
    }

    private val listener = object : MediaPlayerCore.Listener {
        override fun onPrepared(durationMs: Long) {
            errors = 0
            val s = _state.value
            publish(s.copy(durationMs = durationMs.takeIf { it > 0 } ?: s.durationMs, positionMs = core?.positionMs ?: s.positionMs, anchorAt = now()))
        }

        override fun onCompletion() {
            val next = _state.value.queue.advance()
            if (next == null) {
                // End of the queue: back to the first track, paused, like any player.
                val s = _state.value
                load(s.queue.copy(position = 0), autoplay = false)
                focus.abandon()
            } else {
                load(next, autoplay = true)
            }
        }

        override fun onError() {
            // A missing or unreadable file: skip it, but don't spin through a queue of broken ones.
            errors++
            loadedTrackId = null
            val s = _state.value
            if (errors >= s.queue.order.size.coerceAtLeast(1)) {
                publish(s.copy(isPlaying = false))
            } else {
                load(s.queue.skip(1), autoplay = s.isPlaying)
            }
        }
    }

    private fun releasePlayer() {
        core?.release()
        core = null
        loadedTrackId = null
    }

    private fun publish(next: PlaybackState) {
        _state.value = next
        updateNoisy(next.isPlaying)
        if (!next.queue.isEmpty) updateSession(next)
        MusicService.sync(context, next)
    }

    private fun updateNoisy(playing: Boolean) {
        if (playing == noisyRegistered) return
        noisyRegistered = playing
        if (playing) {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } else {
            runCatching { context.unregisterReceiver(noisy) }
        }
    }

    private fun session(): MediaSession = session ?: MediaSession(context, "SideKit Music").also { s ->
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(pos: Long) = seekTo(pos)
            override fun onStop() = stop()
        }, main)
        s.isActive = true
        session = s
    }

    private var sessionTrack: Long? = null

    private fun updateSession(s: PlaybackState) {
        val session = session()
        val track = s.track ?: return
        if (sessionTrack != track.id || s.durationMs > 0) {
            sessionTrack = track.id
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs.takeIf { it > 0 } ?: track.durationMs)
                    .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build(),
            )
        }
        session.setPlaybackState(
            SessionState.Builder()
                .setActions(
                    SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or SessionState.ACTION_PLAY_PAUSE or
                        SessionState.ACTION_SKIP_TO_NEXT or SessionState.ACTION_SKIP_TO_PREVIOUS or
                        SessionState.ACTION_SEEK_TO or SessionState.ACTION_STOP,
                )
                .setState(if (s.isPlaying) SessionState.STATE_PLAYING else SessionState.STATE_PAUSED, s.positionMs, if (s.isPlaying) 1f else 0f, s.anchorAt)
                .build(),
        )
    }

    private fun loadArt(track: Track) {
        artJob?.cancel()
        art = null
        artJob = scope.launch {
            art = Thumbnails.load(context, albumArtUri(track.albumId), ART_PX, ART_PX)
            sessionTrack = null
            val s = _state.value
            if (s.track?.id == track.id) {
                updateSession(s)
                MusicService.sync(context, s)
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        /** A paused player gives its decoder back after this long; play reopens at the same spot. */
        const val IDLE_RELEASE_MS = 5 * 60_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        private const val ART_PX = 128

        @Volatile private var instance: MusicPlayback? = null

        fun get(context: Context): MusicPlayback = instance ?: synchronized(this) {
            instance ?: MusicPlayback(context.applicationContext).also { instance = it }
        }

        /** The player if music has been used in this process; never creates one. */
        fun peek(): MusicPlayback? = instance
    }
}
