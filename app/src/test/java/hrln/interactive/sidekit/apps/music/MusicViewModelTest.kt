package hrln.interactive.sidekit.apps.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicViewModelTest {
    private val vm = MusicViewModel()
    private val state get() = vm.state.value
    private val library = MusicSampleData.library

    @Test fun startsPlayingNoHesitation() {
        assertEquals("No Hesitation", state.nowPlaying?.title)
        assertTrue(state.isPlaying)
    }

    @Test fun playPauseToggles() {
        vm.togglePlayPause()
        assertFalse(state.isPlaying)
        vm.togglePlayPause()
        assertTrue(state.isPlaying)
    }

    @Test fun playPauseIsNoOpWithoutTrack() {
        val empty = MusicViewModel(MusicUiState(queue = emptyList(), queueIndex = 0, isPlaying = false))
        empty.togglePlayPause()
        assertFalse(empty.state.value.isPlaying)
    }

    @Test fun skipNextWrapsToFirst() {
        vm.playAlbum(1)
        val queue = state.queue
        repeat(queue.size - 1) { vm.skipNext() }
        assertEquals(queue.last(), state.nowPlaying?.id)
        vm.skipNext()
        assertEquals(0, state.queueIndex)
        assertEquals("Our Mission", state.nowPlaying?.title)
    }

    @Test fun skipPreviousWrapsToLast() {
        vm.playAlbum(1)
        vm.skipPrevious()
        assertEquals(state.queue.size - 1, state.queueIndex)
        assertEquals("No Hesitation", state.nowPlaying?.title)
    }

    @Test fun skipKeepsPausedState() {
        vm.togglePlayPause()
        vm.skipNext()
        assertFalse(state.isPlaying)
    }

    @Test fun skipIsNoOpOnEmptyQueue() {
        val empty = MusicViewModel(MusicUiState(queue = emptyList(), queueIndex = 0))
        empty.skipNext()
        empty.skipPrevious()
        assertEquals(0, empty.state.value.queueIndex)
    }

    @Test fun playTrackQueuesItsAlbumFromThatTrack() {
        vm.togglePlayPause()
        vm.playTrack(3)
        assertEquals("A Little Lesson", state.nowPlaying?.title)
        assertEquals(library.album(1)!!.trackIds, state.queue)
        assertTrue(state.isPlaying)
    }

    @Test fun playPlaylistStartsAtFirstTrack() {
        vm.playPlaylist(2)
        assertEquals(listOf(2, 4), state.queue)
        assertEquals("The Search For the Cursed Spirit", state.nowPlaying?.title)
    }

    @Test fun playingEmptyAlbumKeepsCurrentTrack() {
        vm.playAlbum(2)
        assertEquals("No Hesitation", state.nowPlaying?.title)
    }

    @Test fun searchIsCaseInsensitive() {
        vm.updateQuery("steve LA")
        assertEquals("steve LA", state.query)
        assertEquals(listOf(LibraryItem.ArtistItem(library.artist(1)!!)), state.results)
    }

    @Test fun searchCoversAllKindsInOrder() {
        val results = library.search("a little")
        assertEquals(
            listOf(LibraryItem.AlbumItem(library.album(3)!!), LibraryItem.TrackItem(library.track(3)!!)),
            results,
        )
    }

    @Test fun blankQueryHasNoResults() {
        vm.updateQuery("   ")
        assertTrue(state.results.isEmpty())
        vm.updateQuery("playlist")
        assertEquals(4, state.results.size)
        vm.clearSearch()
        assertEquals("", state.query)
        assertTrue(state.results.isEmpty())
    }

    @Test fun queryIsTrimmed() {
        assertEquals(library.search("clairo"), library.search("  Clairo "))
        assertEquals(1, library.search("  Clairo ").size)
    }
}
