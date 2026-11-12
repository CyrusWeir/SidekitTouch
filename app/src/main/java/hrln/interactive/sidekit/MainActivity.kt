package hrln.interactive.sidekit

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import hrln.interactive.sidekit.apps.settings.SidekitPreferences
import hrln.interactive.sidekit.apps.settings.rememberCaseColour
import hrln.interactive.sidekit.apps.settings.rememberCompactLayout
import hrln.interactive.sidekit.apps.sidemessage.IncomingLinks
import hrln.interactive.sidekit.apps.sideport.SideportLinks
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.input.HoldToAct
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.NavControls
import hrln.interactive.sidekit.design.input.SystemKeys
import hrln.interactive.sidekit.design.theme.SidekitColors
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.navigation.SidekitNavHost
import hrln.interactive.sidekit.onboarding.OnboardingScreen

class MainActivity : ComponentActivity() {
    /** Bumped when Home is pressed while SideKit is already in front; the nav host returns to the launcher. */
    private val homeRequests = mutableIntStateOf(0)

    /** The swappable keypad. T9 typing and its dictionary only load once T9 is actually used. */
    private val keypad by lazy { Keypad.create(this, lifecycleScope) }

    /** The shared title + action bar; held here so hardware keys (Options) can reach it. */
    private val chrome = ChromeState()

    /** The Sundial has no Back/Options key: hold Tab / Enter, and hold Previous/Next for left/right. */
    private val sundialHold = HoldToAct(onHold = { action, source -> performSundialHold(action, source) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened from an sms: link or a message notification: go to that conversation.
        if (savedInstanceState == null && !SideportLinks.handle(intent)) IncomingLinks.handle(intent)
        enableEdgeToEdge()
        // SideKit uses the whole display; the launcher header carries the clock.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val prefs = remember { SidekitPreferences(this) }
            var setupComplete by remember { mutableStateOf(prefs.setupComplete) }
            val caseColour = rememberCaseColour(prefs)
            // Compact layout (Settings) trims the header and the action bar on every screen.
            val compact = rememberCompactLayout(prefs)
            val spacing = remember(compact) { if (compact) SidekitSpacing.Compact else SidekitSpacing() }
            SidekitTheme(colors = caseColour?.palette() ?: SidekitColors(), spacing = spacing) {
                SidekitCanvas(chrome = chrome, keypad = keypad) {
                    if (setupComplete) {
                        SidekitNavHost(homeRequests = homeRequests.intValue)
                    } else {
                        OnboardingScreen(onFinish = { setupComplete = true })
                    }
                }
            }
        }
        // The canvas paints every pixel, so the window background would only be overdraw: one
        // extra full-screen fill per frame on a GPU (PowerVR GE8320) that is fill-rate bound.
        // It stays in the theme so the first frame before Compose draws is black, not garbage.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    override fun onResume() {
        super.onResume()
        hrln.interactive.sidekit.apps.settings.SidekitPreferences(this).applyInputDefault(keypad)
    }

    override fun onPause() {
        sundialHold.reset()
        super.onPause()
    }

    // Overriding dispatchKeyEvent is the only way to see a keypad event before Compose or the
    // system consumes it; lint flags the override because ComponentActivity marks it @RestrictTo.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Detect the module from the raw key before navigation rewrites it, so a first QWERTY press
        // on a letter that is also a navigator (T/D/B/J) still reveals the module.
        keypad.observe(event)
        // Keypad mapping first, plus each module's default navigation, so Options, Back, focus and
        // typing all see the mapped key.
        val translated = keypad.translateForUi(event) ?: return true
        when (val hold = sundialHold.handle(translated, ::sundialHoldAction)) {
            HoldToAct.Result.Pass -> Unit

            HoldToAct.Result.Consume -> return true

            is HoldToAct.Result.Deliver -> {
                hold.events.forEach(::dispatchTranslated)
                return true
            }
        }
        return dispatchTranslated(translated)
    }

    /**
     * Performs a Sundial hold after the timer fires or the key is released late.
     *
     * Back comes from holding a hardware key, not from a system back gesture, so the
     * predictive-back lint does not apply; the dispatcher still routes through registered
     * callbacks.
     */
    @SuppressLint("GestureBackNavigation")
    private fun performSundialHold(action: Int, source: KeyEvent) {
        when (action) {
            KeyEvent.KEYCODE_BACK -> onBackPressedDispatcher.onBackPressed()

            KeyEvent.KEYCODE_ENDCALL -> {
                keypad.finishTyping()
                chrome.toggleOptions()
            }

            else -> dispatchNav(action, source)
        }
    }

    /** The Sundial keys that get a hold action, and only while its layout is auto-detected. */
    private fun sundialHoldAction(code: Int): Int? {
        if (keypad.module != KeypadModule.Sundial || keypad.mapper.customModule != null) return null
        return NavControls.sundialHoldFor[code]
    }

    /** Sends a held Sundial key's navigation action as a normal press/release. */
    private fun dispatchNav(code: Int, from: KeyEvent) {
        val down = KeyEvent(
            from.downTime, from.eventTime, KeyEvent.ACTION_DOWN, code, 0,
            from.metaState, from.deviceId, from.scanCode, from.flags, from.source
        )
        val up = KeyEvent(
            from.downTime, from.eventTime + 1, KeyEvent.ACTION_UP, code, 0,
            from.metaState, from.deviceId, from.scanCode, from.flags, from.source
        )
        dispatchTranslated(down)
        dispatchTranslated(up)
    }

    /** Routes an already-translated event; also used for the tap released by [sundialHold]. */
    @SuppressLint("RestrictedApi")
    private fun dispatchTranslated(event: KeyEvent): Boolean {
        when {
            // Keypad top-right: the universal Options key. Acts on release; the press is consumed
            // so the system's end-call handling never sees it.
            SystemKeys.isOptions(event) -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    keypad.finishTyping()
                    chrome.toggleOptions()
                }
                return true
            }

            // Keypad top-left (CALL) is turned into Back by KeypadAccessibilityService before the
            // system can open the dialer. Without the service only its release reaches us, after
            // the dialer has launched: swallow it so nothing happens twice.
            SystemKeys.isBack(event) -> return true
        }
        return keypad.dispatch(event) || super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: pressing Home while an app is open delivers the HOME intent here.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeRequests.intValue++
        } else if (!SideportLinks.handle(intent)) {
            IncomingLinks.handle(intent)
        }
    }
}
