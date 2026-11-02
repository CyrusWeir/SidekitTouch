package hrln.interactive.sidekit.apps.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.view.SurfaceHolder

/**
 * The player Music and Video share: a thin, state-safe wrapper over the platform [MediaPlayer].
 *
 * Why [MediaPlayer] and not media3/ExoPlayer: everything here is a local file the platform
 * decoders already play (MP3, AAC, FLAC, Ogg, H.264/HEVC MP4). MediaPlayer decodes in the system
 * media server, so codec buffers are not charged to SideKit's heap, and it costs no APK size.
 * ExoPlayer would add ~1.5 MB of dex plus its own loader/renderer threads and Java-side buffers,
 * for streaming and DRM features the Sidephone doesn't use.
 *
 * One instance plays one item at a time and is reused across items ([MediaPlayer.reset]), so
 * moving through a queue doesn't churn native players. Calls that MediaPlayer would reject in its
 * current state (seek before prepare, position after an error) are absorbed here.
 */
class MediaPlayerCore(
    private val context: Context,
    private val attributes: AudioAttributes,
    /** Keep the CPU awake while playing (music with the screen off). Needs WAKE_LOCK. */
    private val keepCpuAwake: Boolean,
) {
    interface Listener {
        fun onPrepared(durationMs: Long) {}
        fun onCompletion() {}
        fun onError() {}
        fun onVideoSize(width: Int, height: Int) {}
    }

    var listener: Listener? = null

    private var player: MediaPlayer? = null
    private var holder: SurfaceHolder? = null

    var isPrepared: Boolean = false
        private set
    private var playWhenReady = false
    private var pendingSeekMs = 0L

    val isPlaying: Boolean get() = isPrepared && runCatching { player?.isPlaying == true }.getOrDefault(false)

    val positionMs: Long
        get() = if (isPrepared) runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else pendingSeekMs

    val durationMs: Long
        get() = if (isPrepared) runCatching { player?.duration?.toLong()?.coerceAtLeast(0) ?: 0L }.getOrDefault(0L) else 0L

    /** Starts loading [uri]. Playback starts once prepared if [autoplay]. */
    fun open(uri: Uri, startMs: Long = 0, autoplay: Boolean = true) {
        val p = player ?: create().also { player = it }
        isPrepared = false
        playWhenReady = autoplay
        pendingSeekMs = startMs.coerceAtLeast(0)
        try {
            p.reset()
            p.setAudioAttributes(attributes)
            if (keepCpuAwake) p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            holder?.let { p.setDisplay(it) }
            p.setDataSource(context, uri)
            p.prepareAsync()
        } catch (_: Exception) {
            listener?.onError()
        }
    }

    private fun create() = MediaPlayer().apply {
        setOnPreparedListener { mp ->
            isPrepared = true
            if (pendingSeekMs > 0) mp.seekTo(pendingSeekMs, MediaPlayer.SEEK_CLOSEST)
            if (playWhenReady) mp.start()
            listener?.onPrepared(mp.duration.toLong().coerceAtLeast(0))
        }
        setOnCompletionListener { listener?.onCompletion() }
        setOnErrorListener { _, _, _ ->
            isPrepared = false
            listener?.onError()
            true
        }
        setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) listener?.onVideoSize(w, h) }
    }

    fun play() {
        playWhenReady = true
        if (isPrepared) runCatching { player?.start() }
    }

    fun pause() {
        playWhenReady = false
        if (isPrepared) runCatching { player?.pause() }
    }

    fun seekTo(ms: Long) {
        pendingSeekMs = ms.coerceAtLeast(0)
        if (isPrepared) runCatching { player?.seekTo(pendingSeekMs, MediaPlayer.SEEK_CLOSEST) }
    }

    fun setVolume(volume: Float) {
        runCatching { player?.setVolume(volume, volume) }
    }

    /** Video output. Null detaches it (the surface is going away) without stopping audio. */
    fun setDisplay(surface: SurfaceHolder?) {
        holder = surface
        runCatching { player?.setDisplay(surface) }
    }

    /** Frees the native player and its codecs. The core can be [open]ed again afterwards. */
    fun release() {
        isPrepared = false
        playWhenReady = false
        player?.let { p ->
            runCatching { p.setDisplay(null) }
            p.release()
        }
        player = null
    }
}

/**
 * Audio focus for one player. Losing focus for good pauses; a transient loss (a call, a
 * notification) pauses and resumes when focus returns. Ducking is left to the system.
 */
class AudioFocus(
    context: Context,
    attributes: AudioAttributes,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var resumeOnGain = false
    private var held = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    held = false
                    resumeOnGain = false
                    onPause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnGain = true
                    onPause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    held = true
                    if (resumeOnGain) onResume()
                    resumeOnGain = false
                }
            }
        }
        .build()

    /** True if playback may start. */
    fun request(): Boolean {
        if (held) return true
        held = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        resumeOnGain = false
        return held || audio == null
    }

    fun abandon() {
        resumeOnGain = false
        if (!held) return
        held = false
        audio?.abandonAudioFocusRequest(request)
    }
}
