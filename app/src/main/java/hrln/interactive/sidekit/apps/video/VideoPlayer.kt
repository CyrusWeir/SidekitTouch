package hrln.interactive.sidekit.apps.video

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hrln.interactive.sidekit.apps.media.AudioFocus
import hrln.interactive.sidekit.apps.media.MediaPlayerCore

/** Seek step for the bar's skip button, keypad 4/6 and left/right. */
const val SEEK_STEP_MS = 10_000L

/** [position] moved by [delta], kept inside the video. */
fun seekTarget(position: Long, delta: Long, duration: Long): Long {
    val target = (position + delta).coerceAtLeast(0)
    return if (duration > 0) target.coerceAtMost(duration) else target
}

/**
 * Full-screen playback of one video. Holds a [MediaPlayerCore] only between [start] and [stop]
 * (the screen's STARTED lifetime): leaving the screen or turning the display off frees the
 * decoder, and coming back reopens at the same spot, paused.
 */
@Stable
class VideoPlayer(private val context: Context, private val uri: Uri) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private var core: MediaPlayerCore? = null
    private val focus = AudioFocus(context, attributes, onPause = ::pause, onResume = ::play)
    private var display: SurfaceHolder? = null

    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Width / height of the picture, once known. */
    var aspect by mutableStateOf(0f)
        private set

    /** Position at [anchorAt] (elapsed realtime); [positionMs] extrapolates from it while playing. */
    private var anchorMs by mutableLongStateOf(0L)
    private var anchorAt = 0L

    fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) anchorMs + (now - anchorAt) else anchorMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    private fun anchor(ms: Long) {
        anchorMs = ms
        anchorAt = SystemClock.elapsedRealtime()
    }

    /** Opens the player. [autoplay] for the first open; returning to the screen reopens paused. */
    fun start(autoplay: Boolean) {
        if (core != null) return
        val c = MediaPlayerCore(context, attributes, keepCpuAwake = false)
        c.listener = object : MediaPlayerCore.Listener {
            override fun onPrepared(durationMs: Long) {
                this@VideoPlayer.durationMs = durationMs
                failed = false
                if (c.isPlaying) {
                    isPlaying = true
                    anchor(c.positionMs)
                }
            }

            override fun onCompletion() {
                isPlaying = false
                ended = true
                anchor(durationMs)
                focus.abandon()
            }

            override fun onError() {
                isPlaying = false
                failed = true
                focus.abandon()
            }

            override fun onVideoSize(width: Int, height: Int) {
                aspect = width / height.toFloat()
            }
        }
        display?.let(c::setDisplay)
        core = c
        val play = autoplay && focus.request()
        c.open(uri, startMs = anchorMs, autoplay = play)
        if (!play) isPlaying = false
    }

    /** Frees the decoder, remembering where playback was. */
    fun stop() {
        val c = core ?: return
        anchor(positionMs())
        c.release()
        core = null
        isPlaying = false
        focus.abandon()
    }

    fun play() {
        val c = core ?: return
        if (!focus.request()) return
        if (ended) {
            ended = false
            c.seekTo(0)
            anchor(0)
        }
        c.play()
        isPlaying = true
        anchor(if (c.isPrepared) c.positionMs else anchorMs)
    }

    fun pause() {
        val c = core ?: return
        anchor(positionMs())
        c.pause()
        isPlaying = false
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    fun seekTo(ms: Long) {
        val target = seekTarget(ms, 0, durationMs)
        ended = false
        core?.seekTo(target)
        anchor(target)
    }

    fun seekBy(delta: Long) = seekTo(seekTarget(positionMs(), delta, durationMs))

    fun attach(holder: SurfaceHolder?) {
        display = holder
        core?.setDisplay(holder)
    }
}
