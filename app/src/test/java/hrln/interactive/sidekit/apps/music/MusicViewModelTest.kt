package hrln.interactive.sidekit.apps.music

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicViewModelTest {
    private val tracks = listOf(
        Track(1, "Our Mission", 1, 3, "Yoshiruma Terui", "Hidden Inventory", 1000),
        Track(2, "A Little Lesson", 1, 3, "Yoshiruma Terui", "Hidden Inventory", 2000),
    )
    private val player = FakePlayer()
    private val source = object : MusicSource {
        override fun tracks() = tracks
        override fun changes(): Flow<Unit> = flowOf(Unit)
    }
    private val store = object : PlaylistStore {
        override fun read() = listOf(Playlist(8, "Favourites", listOf(2)))
        override fun write(playlists: List<Playlist>) = Unit
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun playTrackQueuesItsAlbumFromThatTrack() = runTest {
        val vm = MusicViewModel(source, PlaylistRepository(store, backgroundScope, UnconfinedTestDispatcher()), player, UnconfinedTestDispatcher())
        vm.state.first { it.loaded }
        vm.playTrack(2)
        assertEquals(tracks.sortedBy { it.title }, player.tracks)
        assertEquals(0, player.start)
        vm.updateQuery("lesson")
        val search = vm.state.first { it.query == "lesson" }
        assertEquals("A Little Lesson", (search.results.single() as LibraryItem.TrackItem).track.title)
        vm.viewModelScope.cancel()
    }

    @Test fun persistentPlaylistPlaysFromFirstTrack() = runTest {
        val vm = MusicViewModel(source, PlaylistRepository(store, backgroundScope, UnconfinedTestDispatcher()), player, UnconfinedTestDispatcher())
        vm.state.first { it.loaded }
        vm.playPlaylist(8)
        assertEquals(listOf(tracks[1]), player.tracks)
        vm.saveTracks("New", tracks)
        assertEquals(2, vm.state.first { it.library.playlists.size == 2 }.library.playlists.size)
        vm.viewModelScope.cancel()
    }

    @Test fun shuffleKeepsCurrentTrackAndRepeatCycles() {
        val queue = PlayQueue.of(tracks, 1, shuffle = false, repeat = RepeatMode.Off)
        val shuffled = queue.withShuffle(true)
        assertEquals(tracks[1], shuffled.current)
        assertEquals(2, shuffled.order.size)
        assertEquals(RepeatMode.All, RepeatMode.Off.next())
        assertTrue(queue.withRepeat(RepeatMode.One).advance()!!.repeat == RepeatMode.One)
    }

    private class FakePlayer : MusicPlayer {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        var tracks: List<Track> = emptyList()
        var start = -1
        override fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean?) { this.tracks = tracks; start = startIndex }
        override fun togglePlayPause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun setShuffle(on: Boolean) = Unit
        override fun cycleRepeat() = Unit
        override fun jumpTo(playPosition: Int) = Unit
    }
}
