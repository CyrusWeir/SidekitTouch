package hrln.interactive.sidekit.apps.photos

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.ui.SidekitPreviewFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val items = (1L..20L).map { MediaItem(it) }
    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
    @Test fun main() = shoot("photos_36-41") { PhotosMainScreen(GridState(items, loaded = true), {}, {}) }
    @Test fun albums() = shoot("photos_albums_36-92") { AlbumsScreen(smartAlbums, listOf(PhotoAlbum(AlbumKey.Bucket(2), "Camera", 20)), {}, {}) }
    @Test fun album() = shoot("photos_recent_36-220") { AlbumScreen("Recent", GridState(items, loaded = true), {}, {}) }
    @Test fun photoView() = shoot("photos_view_71-2165") { PhotoViewScreen(items, 0, emptySet(), {}, {}, {}, {}, {}) }
}
