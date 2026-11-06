package hrln.interactive.sidekit.apps.sideport.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import hrln.interactive.sidekit.apps.sideport.registry.PortMode
import hrln.interactive.sidekit.apps.sideport.ui.OriginalPill
import hrln.interactive.sidekit.apps.sideport.ui.ProjectionOverlay
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The window the projection is drawn in: a full-screen accessibility overlay over the ported app.
 *
 * Accessibility overlays are the one window type that doesn't hide what's below it from
 * accessibility: the app stays resumed and readable underneath. The window is not focusable by
 * default, so the app keeps input focus (video keeps playing, its own focus logic keeps working)
 * and keys reach the projection through the service's key filter. It turns focusable only while
 * the screen has text fields, so typing (T9 or the soft keyboard) lands in Sidekit's fields.
 *
 * Compose needs the owners an activity normally provides; this window provides them itself.
 * [PortMode.Original] swaps the full window for a small "Sidekit" pill over the real app.
 */
class OverlayHost(private val context: Context, private val controller: ProjectionController) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val dispatcher = OnBackPressedDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = dispatcher

    val chrome = ChromeState()
    val keypad: Keypad = Keypad.create(context, scope)

    private var full: View? = null
    private var pill: View? = null
    private var fullParams: WindowManager.LayoutParams? = null

    var focusable: Boolean = false
        private set
    private var imeHeight = 0

    /** Physical screen size, which the interpreter measures regions against. */
    val screenSize: Pair<Int, Int>
        get() = windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show(mode: PortMode) {
        when (mode) {
            PortMode.Projected -> { removePill(); addFull() }
            PortMode.Original -> { removeFull(); addPill() }
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Focusable while there are fields to type in; shortened above a visible soft keyboard. */
    fun update(focusable: Boolean, imeHeight: Int) {
        val params = fullParams ?: return
        val ime = if (focusable) imeHeight else 0
        if (focusable == this.focusable && ime == this.imeHeight) return
        this.focusable = focusable
        this.imeHeight = ime
        params.flags = baseFlags() or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.height = if (ime > 0) screenSize.second - ime else WindowManager.LayoutParams.MATCH_PARENT
        full?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /** Back for the projection's own layers (options bar, typing). True if one of them took it. */
    fun consumeBack(): Boolean {
        if (!dispatcher.hasEnabledCallbacks()) return false
        dispatcher.onBackPressed()
        return true
    }

    /** A key that arrived through the service's filter, for the projection's focused element. */
    fun dispatchToContent(event: KeyEvent) {
        (full as? ViewGroupHost)?.content?.dispatchKeyEvent(event)
    }

    fun destroy() {
        removeFull()
        removePill()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        scope.cancel()
    }

    // ---- Windows ------------------------------------------------------------------------------

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun addFull() {
        if (full != null) return
        val compose = composeView().apply {
            setContent {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides this@OverlayHost) {
                    SidekitTheme {
                        ProjectionOverlay(controller.state, controller, chrome, keypad, screenSize.second)
                    }
                }
            }
        }
        val host = ViewGroupHost(context, compose)
        attachOwners(host)
        focusable = false
        imeHeight = 0
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            // The projection paints every pixel; an opaque window lets the compositor skip the app below.
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Sideport projection"
        }
        windowManager.addView(host, params)
        full = host
        fullParams = params
    }

    private fun addPill() {
        if (pill != null) return
        val compose = composeView().apply {
            setContent { SidekitTheme { OriginalPill(controller.state.app.label) { controller.showProjection() } } }
        }
        attachOwners(compose)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = PILL_MARGIN_PX
            y = PILL_MARGIN_PX
            title = "Sideport pill"
        }
        windowManager.addView(compose, params)
        pill = compose
    }

    private fun removeFull() {
        full?.let { view ->
            (view as? ViewGroupHost)?.content?.disposeComposition()
            runCatching { windowManager.removeView(view) }
        }
        full = null
        fullParams = null
        focusable = false
    }

    private fun removePill() {
        pill?.let { view ->
            (view as? ComposeView)?.disposeComposition()
            runCatching { windowManager.removeView(view) }
        }
        pill = null
    }

    private fun composeView() = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeOnBackPressedDispatcherOwner(this)
    }

    /** Root of the full window: hands keys to the controller when the window has focus. */
    @SuppressLint("ViewConstructor")
    private inner class ViewGroupHost(context: Context, val content: ComposeView) : FrameLayout(context) {
        init { addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

        override fun dispatchKeyEvent(raw: KeyEvent): Boolean {
            val event = keypad.translateForUi(raw) ?: return true
            val special = event.keyCode == KeyEvent.KEYCODE_BACK || hrln.interactive.sidekit.design.input.SystemKeys.isOptions(event)
            if (special) return controller.onKey(event, fromFilter = false)
            if (keypad.dispatch(event)) return true
            return super.dispatchKeyEvent(event)
        }
    }

    private companion object {
        const val PILL_MARGIN_PX = 16
    }
}
