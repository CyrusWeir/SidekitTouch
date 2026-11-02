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

/** Renders the Photos screens at 480 x 640 to app/screenshots/ for comparison with the Figma frames. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val data = PhotosSampleData

    @Test fun main() = shoot("photos_36-41") { PhotosMainScreen(data.photos, {}, {}) }

    @Test fun albums() = shoot("photos_albums_36-92") { AlbumsScreen(data.smartAlbums, data.userAlbums, {}, {}, {}) }

    @Test fun recent() = shoot("photos_recent_36-220") {
        val album = data.album(1)!!
        AlbumScreen(album, data.photosIn(album), {}, {})
    }

    @Test fun album1() = shoot("photos_album1_36-286") {
        val album = data.album(5)!!
        AlbumScreen(album, data.photosIn(album), {}, {})
    }

    @Test fun photoView() = shoot("photos_view_71-2165") { PhotoViewScreen(Photo(1), {}) }
}
