package hrln.interactive.sidekit.apps.sideport.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import hrln.interactive.sidekit.apps.sideport.SideportLinks
import hrln.interactive.sidekit.apps.sideport.interpret.Block
import hrln.interactive.sidekit.apps.sideport.interpret.ButtonBlock
import hrln.interactive.sidekit.apps.sideport.interpret.FieldBlock
import hrln.interactive.sidekit.apps.sideport.interpret.Interpreter
import hrln.interactive.sidekit.apps.sideport.interpret.ItemBlock
import hrln.interactive.sidekit.apps.sideport.interpret.ListStitcher
import hrln.interactive.sidekit.apps.sideport.interpret.ScreenModel
import hrln.interactive.sidekit.apps.sideport.model.UiNode
import hrln.interactive.sidekit.apps.sideport.model.WindowSnapshot
import hrln.interactive.sidekit.apps.sideport.recipes.RecipeBook
import hrln.interactive.sidekit.apps.sideport.registry.PortMode
import hrln.interactive.sidekit.apps.sideport.registry.PortedApp
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import hrln.interactive.sidekit.design.input.SystemKeys
import java.io.File

/**
 * One session of a ported app: from the moment Sidekit opens it until the user is back in
 * Sidekit. Reads the app's screen on change, interprets it, stitches the main list, and turns
 * presses in the projection back into accessibility actions on the real app.
 *
 * Threads: accessibility callbacks, key events and [state] live on the main thread. Every read of
 * the app and every action on it (binder calls into the app's process) runs on one worker thread,
 * in order, so the UI never waits on the app.
 *
 * Reads are throttled, not debounced: a screen that never stops changing (a clock, a progress bar,
 * a video) is still read, at most every [MIN_READ_GAP_MS].
 */
class ProjectionController(
    private val service: SideportService,
    app: PortedApp,
    private val recipes: RecipeBook,
    private val homePackages: Set<String>,
    private val onEnded: (ProjectionController) -> Unit,
) : ProjectionActions {
    val state = ProjectionState(app)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("sideport-projection").apply { start() }
    private val worker = Handler(thread.looper)
    val overlay = OverlayHost(service, this)

    // Main thread.
    private var active = false
    private var ended = false
    private var refreshPending = false
    private var lastReadAt = 0L

    // Worker thread.
    private val reader = NodeReader()
    private val stitcher = ListStitcher()
    private var latest: ScreenModel? = null
    private var latestTree: UiNode? = null
    private var publishedItems: List<Block> = emptyList()
    private var scrolling = false

    /** Debuggable builds save each new screen's tree once, for writing recipes from real apps. */
    private val debuggable = service.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val dumped = HashSet<String>()

    // Written on main, read on the worker.
    @Volatile private var screenName: String? = null
    @Volatile private var screenPackage: String? = null
    @Volatile private var projecting = app.mode == PortMode.Projected

    private val launchTimeout = Runnable {
        if (!active) {
            Log.w(TAG, "${state.app.packageName} never came to the front")
            end()
        }
    }

    fun start() {
        overlay.show(state.app.mode)
        main.postDelayed(launchTimeout, LAUNCH_TIMEOUT_MS)
        requestRead(LAUNCH_READ_DELAY_MS)
    }

    // ---- Events -------------------------------------------------------------------------------

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != null && pkg != service.packageName) {
                    screenPackage = pkg
                    screenName = event.className?.toString()
                }
                requestRead(STATE_READ_DELAY_MS)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> requestRead(STATE_READ_DELAY_MS)
            else -> if (pkg != service.packageName) requestRead(CONTENT_READ_DELAY_MS)
        }
    }

    /**
     * Hardware keys, from the service's key filter (overlay not focusable) or from the overlay's
     * own window (focusable). Returns true when consumed.
     */
    fun onKey(raw: KeyEvent, fromFilter: Boolean): Boolean {
        if (ended || state.app.mode != PortMode.Projected) return false
        var event = raw
        if (fromFilter) {
            // Our own injected Back (a global action) must reach the app, not loop back here; keys
            // the system must keep pass through too.
            val injectedBack = event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD && event.keyCode == KeyEvent.KEYCODE_BACK
            if (injectedBack || event.keyCode in SYSTEM_KEYS) return false
            if (overlay.focusable) return false
            // The overlay window translates its own keys; these come straight from the filter.
            event = overlay.keypad.translateForUi(raw) ?: return true
        }
        when {
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
                return true
            }
            SystemKeys.isOptions(event) -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    overlay.keypad.finishTyping()
                    overlay.chrome.toggleOptions()
                }
                return true
            }
        }
        if (overlay.keypad.dispatch(event)) return true
        overlay.dispatchToContent(event)
        // While projected, keys never leak into the hidden app.
        return true
    }

    private fun requestRead(delayMs: Long) {
        if (ended || refreshPending) return
        refreshPending = true
        val gap = SystemClock.uptimeMillis() - lastReadAt
        main.postDelayed({
            refreshPending = false
            lastReadAt = SystemClock.uptimeMillis()
            worker.post { read() }
        }, maxOf(delayMs, MIN_READ_GAP_MS - gap))
    }

    // ---- Reading (worker) ---------------------------------------------------------------------

    /** Reads the top app window and publishes the interpreted screen. Returns the model, if any. */
    private fun read(): ScreenModel? {
        if (ended) return null
        val windows = try { service.windows } catch (e: RuntimeException) { return null }
        var imeHeight = 0
        var target: AccessibilityWindowInfo? = null
        for (window in windows.sortedByDescending { it.layer }) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    val r = android.graphics.Rect().also(window::getBoundsInScreen)
                    imeHeight = r.height()
                }
                AccessibilityWindowInfo.TYPE_APPLICATION -> if (target == null) target = window
            }
        }
        val root = target?.root ?: return null.also { Log.d(TAG, "No readable app window") }
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == service.packageName || pkg in homePackages) {
            main.post { onSidekitInFront() }
            return null
        }
        // Showing the original: only watch for Sidekit coming back to the front.
        if (!projecting) {
            main.post { if (!active) { active = true; main.removeCallbacks(launchTimeout) } }
            return null
        }
        val tree = reader.read(root) ?: return null.also { Log.d(TAG, "$pkg has nothing visible") }
        val name = if (pkg == screenPackage) screenName else target.title?.toString()
        val (w, h) = overlay.screenSize
        val snapshot = WindowSnapshot(pkg, name, tree, w, h)
        val model = try {
            Interpreter.interpret(snapshot, recipes.forScreen(snapshot))
        } catch (e: RuntimeException) {
            Log.e(TAG, "Interpreting ${snapshot.packageName} failed", e)
            return null
        }
        val items = stitcher.merge(model.signature, model.items)
        if (debuggable && dumped.add(model.signature) && dumped.size <= MAX_AUTO_DUMPS) writeTree(tree, model.packageName, name)
        // Stitched rows can grow while the app's own window reads the same (a scroll back over known rows).
        val changed = model != latest || items != publishedItems
        publishedItems = items
        latest = model
        latestTree = tree
        main.post { publish(model, items, tree, pkg, imeHeight, changed) }
        return model
    }

    private fun publish(model: ScreenModel, items: List<Block>, tree: UiNode, pkg: String, imeHeight: Int, changed: Boolean) {
        if (ended) return
        if (!active) {
            active = true
            main.removeCallbacks(launchTimeout)
        }
        if (changed || state.model == null) {
            state.model = model
            state.items = items
            state.foreignPackage = pkg.takeIf { it != state.app.packageName }
        }
        if (state.inspector) state.tree = tree
        overlay.update(focusable = model.hasFields, imeHeight = imeHeight)
    }

    private fun onSidekitInFront() {
        // While launching, Sidekit is still the top app until the ported app's window appears.
        if (active) end()
    }

    // ---- Actions ------------------------------------------------------------------------------

    private fun allBlocks(model: ScreenModel) = model.header + model.items + model.footer

    /** The live handle a block's press goes to, looked up by key in the latest read. */
    private fun liveHandle(key: String, long: Boolean): Int? {
        val model = latest ?: return null
        return when (val b = allBlocks(model).firstOrNull { it.key == key }) {
            is ItemBlock -> if (long) b.longClick ?: b.click else b.click ?: b.longClick
            is ButtonBlock -> b.handle
            is FieldBlock -> b.handle
            else -> null
        }
    }

    override fun press(block: Block, long: Boolean) = onWorker {
        val direct = liveHandle(block.key, long)
        val handle = direct ?: seek(block.key)?.let { liveHandle(block.key, long) }
        if (handle == null) {
            main.post { state.notice = "That row has gone from the app." }
            return@onWorker
        }
        perform(handle, if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * A stitched row that has scrolled out of the app: scroll the app back to it. Gives up after
     * [MAX_SEEK_SCROLLS] scrolls (the row may have been deleted meanwhile).
     */
    private fun seek(key: String): Int? {
        val target = stitcher.rows.indexOfFirst { it.key == key }
        if (target < 0) return null
        repeat(MAX_SEEK_SCROLLS) {
            val list = latest?.list ?: return null
            val visible = stitcher.visible
            if (target in visible) return target
            val forward = visible.isEmpty() || target > visible.last
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (reader.node(list.handle)?.performAction(action) != true) return null
            SystemClock.sleep(SEEK_SETTLE_MS)
            read()
            if (latest?.let(::allBlocks)?.any { it.key == key } == true) return target
        }
        return null
    }

    override fun pressAction(handle: Int, key: String) = onWorker {
        val model = latest
        val live = model?.let { m -> (m.actions + m.options).firstOrNull { it.key == key }?.handle } ?: handle
        perform(live, AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun setText(fieldKey: String, text: String) = onWorker {
        val handle = liveHandle(fieldKey, long = false) ?: return@onWorker
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        reader.node(handle)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Scrolls the app until the stitched list gains rows. One scroll isn't always enough: a list
     * seen through a small viewport can land between rows, and a read then brings nothing new.
     */
    override fun loadMore(forward: Boolean) = onWorker {
        if (scrolling) return@onWorker
        scrolling = true
        main.post { state.loadingMore = true }
        val before = stitcher.rows.size
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        for (attempt in 0 until MAX_LOAD_SCROLLS) {
            val list = latest?.list ?: break
            if (forward && !list.canScrollForward || !forward && !list.canScrollBackward) break
            if (reader.node(list.handle)?.performAction(action) != true) break
            SystemClock.sleep(SEEK_SETTLE_MS)
            read()
            if (stitcher.rows.size > before) break
        }
        scrolling = false
        main.post { state.loadingMore = false }
    }

    private fun perform(handle: Int, action: Int) {
        val ok = reader.node(handle)?.performAction(action) == true
        if (!ok) main.post { state.notice = "The app didn't respond to that." }
        main.post { requestRead(ACTION_READ_DELAY_MS) }
    }

    private inline fun onWorker(crossinline block: () -> Unit) {
        worker.post { if (!ended) block() }
    }

    override fun back() {
        if (overlay.consumeBack()) return
        if (state.inspector) { toggleInspector(); return }
        if (overlay.focusable) {
            // Back from a global action goes to the focused window, which is ours while a form is
            // showing: hand focus back to the app first.
            overlay.update(focusable = false, imeHeight = 0)
            main.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, FOCUS_HANDOFF_MS)
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        requestRead(ACTION_READ_DELAY_MS)
    }

    override fun exitToSidekit() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    override fun showOriginal() {
        setMode(PortMode.Original)
    }

    fun showProjection() {
        setMode(PortMode.Projected)
    }

    private fun setMode(mode: PortMode) {
        state.app = state.app.copy(mode = mode)
        projecting = mode == PortMode.Projected
        PortedApps.get(service).setMode(state.app.packageName, mode)
        overlay.show(mode)
        requestRead(0)
    }

    override fun toggleInspector() {
        state.inspector = !state.inspector
        if (state.inspector) worker.post { val t = latestTree; main.post { state.tree = t } }
        else state.tree = null
    }

    override fun exportTree() = onWorker {
        val tree = latestTree ?: return@onWorker
        val message = writeTree(tree, latest?.packageName ?: state.app.packageName, screenName)
        main.post { state.notice = message }
    }

    /** Worker thread. Writes a tree to `files/sideport/dumps/` and says how it went. */
    private fun writeTree(tree: UiNode, pkg: String, screen: String?): String = try {
        val dir = File(service.getExternalFilesDir(null) ?: service.filesDir, "sideport/dumps").apply { mkdirs() }
        val file = File(dir, "$pkg-${System.currentTimeMillis()}.json")
        file.writeText(TreeJson.write(tree, pkg, screen).toString(1))
        "Saved ${file.name}"
    } catch (e: Exception) {
        "Couldn't save: ${e.localizedMessage}"
    }

    override fun openHub() {
        val intent = SideportLinks.hubIntent(service, state.app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
    }

    fun end() {
        if (ended) return
        ended = true
        main.removeCallbacksAndMessages(null)
        overlay.destroy()
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
        onEnded(this)
    }

    private companion object {
        const val TAG = "Sideport"
        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val LAUNCH_READ_DELAY_MS = 300L
        const val STATE_READ_DELAY_MS = 40L
        const val CONTENT_READ_DELAY_MS = 120L
        const val ACTION_READ_DELAY_MS = 160L
        const val MIN_READ_GAP_MS = 180L
        const val SEEK_SETTLE_MS = 220L
        const val MAX_SEEK_SCROLLS = 12
        const val MAX_AUTO_DUMPS = 40
        const val MAX_LOAD_SCROLLS = 6
        const val FOCUS_HANDOFF_MS = 80L
        val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
