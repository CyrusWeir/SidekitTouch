package hrln.interactive.sidekit

import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import hrln.interactive.sidekit.design.input.Keypad
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SidekitNavHost

class MainActivity : ComponentActivity() {
    /** Bumped when Home is pressed while SideKit is already in front; the nav host returns to the launcher. */
    private val homeRequests = mutableIntStateOf(0)

    /** The swappable keypad. T9 typing and its dictionary only load once T9 is actually used. */
    private val keypad by lazy { Keypad.create(this, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The mocks use the whole 480 x 640 display; the launcher header carries the clock.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            SidekitTheme {
                SidekitCanvas(keypad = keypad) {
                    SidekitNavHost(homeRequests = homeRequests.intValue)
                }
            }
        }
        // The canvas paints every pixel, so the window background would only be overdraw: one
        // extra full-screen fill per frame on a GPU (PowerVR GE8320) that is fill-rate bound.
        // It stays in the theme so the first frame before Compose draws is black, not garbage.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keypad.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: pressing Home while an app is open delivers the HOME intent here.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeRequests.intValue++
    }
}
