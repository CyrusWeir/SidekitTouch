package hrln.interactive.sidekit.apps.music

import androidx.compose.runtime.Immutable
import kotlin.random.Random

enum class RepeatMode(val label: String) {
    Off("Repeat off"),
    All("Repeat all"),
    One("Repeat one");

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/**
 * What's playing and what comes next. Pure and immutable, so every rule is unit-tested.
 *
 * [tracks] is the list as the user chose it (album order, playlist order); [order] is the play
 * order as indices into it: identity normally, a shuffle with the current track first when
 * [shuffle] is on. Turning shuffle off continues from the same track in list order.
 */
@Immutable
data class PlayQueue(
    val tracks: List<Track> = emptyList(),
    val order: List<Int> = tracks.indices.toList(),
    val position: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
) {
    val current: Track? get() = order.getOrNull(position)?.let(tracks::getOrNull)
    val isEmpty: Boolean get() = current == null

    /** Tracks after the current one, in play order (Now Playing's "Up next"). */
    val upNext: List<Track> get() = if (isEmpty) emptyList() else order.drop(position + 1).map(tracks::get)

    /** Next / previous by the user: wraps around the ends, whatever the repeat mode. */
    fun skip(delta: Int): PlayQueue = if (order.isEmpty()) this else copy(position = Math.floorMod(position + delta, order.size))

    /**
     * The queue after the current track ends on its own, or null when playback should stop:
     * Repeat one replays, Repeat all wraps, otherwise the last track ends the queue.
     */
    fun advance(): PlayQueue? = when {
        order.isEmpty() -> null
        repeat == RepeatMode.One -> this
        position + 1 < order.size -> copy(position = position + 1)
        repeat == RepeatMode.All -> copy(position = 0)
        else -> null
    }

    /** Plays the track at [playPosition] in play order (a row in "Up next"). */
    fun jumpTo(playPosition: Int): PlayQueue = if (playPosition in order.indices) copy(position = playPosition) else this

    fun withShuffle(on: Boolean, random: Random = Random.Default): PlayQueue {
        if (on == shuffle || order.isEmpty()) return copy(shuffle = on)
        val now = order[position]
        return if (on) {
            copy(order = listOf(now) + (tracks.indices - now).shuffled(random), position = 0, shuffle = true)
        } else {
            copy(order = tracks.indices.toList(), position = now, shuffle = false)
        }
    }

    fun withRepeat(mode: RepeatMode): PlayQueue = copy(repeat = mode)

    companion object {
        /** A queue of [tracks] starting at [startIndex], keeping the user's shuffle and repeat. */
        fun of(tracks: List<Track>, startIndex: Int, shuffle: Boolean, repeat: RepeatMode, random: Random = Random.Default): PlayQueue {
            val start = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            val plain = PlayQueue(tracks, tracks.indices.toList(), start, shuffle = false, repeat = repeat)
            return if (shuffle) plain.withShuffle(true, random) else plain
        }
    }
}
