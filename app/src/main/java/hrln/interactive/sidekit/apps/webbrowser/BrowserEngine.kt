package hrln.interactive.sidekit.apps.webbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the chrome shows of the live page. [progress] is read in the draw phase only. */
@Stable
class PageState {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
}

/** What the page asks of the browser around it. */
interface BrowserHost {
    fun onPage(tabId: Long, url: String, title: String)
    fun onVisited(url: String, title: String)
    fun onNotice(message: String)

    /** The renderer died (killed for memory, usually); the WebView must be replaced. */
    fun onRendererGone()
}

/**
 * The browser's one live [WebView], for one tab. Other tabs have no view at all, only their
 * address, saved back/forward list and a thumbnail ([BrowserTabMemory]). Switching tabs destroys
 * this engine and builds another, which keeps the browser's RAM to a single renderer.
 *
 * Settings are safe defaults: no file or content access, mixed content blocked, no geolocation,
 * no pop-up windows, media needs a tap, Safe Browsing on (the platform default).
 */
class BrowserEngine private constructor(
    private val context: Context,
    val tabId: Long,
    val view: WebView,
    private val host: BrowserHost,
) {
    val page = PageState()
    private var destroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(textZoom: Int) {
        view.settings.apply {
            javaScriptEnabled = true // Most of the web needs it; everything else stays locked down.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            this.textZoom = textZoom
        }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
    }

    fun load(url: String) {
        if (destroyed || url.isEmpty()) return
        view.loadUrl(url)
    }

    /** Puts back a saved back/forward list; false if there was none. Only on a fresh WebView. */
    fun restore(state: Bundle?): Boolean = state != null && view.restoreState(state) != null

    fun goBack() { if (!destroyed && view.canGoBack()) view.goBack() }
    fun goForward() { if (!destroyed && view.canGoForward()) view.goForward() }
    fun reload() { if (!destroyed) view.reload() }
    fun stop() { if (!destroyed) view.stopLoading() }

    fun setTextZoom(percent: Int) {
        if (!destroyed) view.settings.textZoom = percent
    }

    /** Stops scripts, animations and media while the page isn't on screen. */
    fun pause() { if (!destroyed) view.onPause() }
    fun resume() { if (!destroyed) view.onResume() }

    /** A small picture of what's on screen now, for the tab list. */
    fun captureThumbnail() {
        if (destroyed) return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        val th = (THUMB_W * h / w).coerceIn(1, THUMB_H_MAX)
        try {
            val bitmap = Bitmap.createBitmap(THUMB_W, th, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val scale = THUMB_W.toFloat() / w
            canvas.scale(scale, scale)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)
            BrowserTabMemory.putThumbnail(tabId, bitmap)
        } catch (_: RuntimeException) {
            // A thumbnail is a nicety.
        }
    }

    /** Keeps the tab's history for later and frees the WebView and its renderer. */
    fun saveAndDestroy() {
        if (destroyed) return
        captureThumbnail()
        val state = Bundle()
        if (view.saveState(state) != null) BrowserTabMemory.putState(tabId, state)
        destroyed = true
        view.stopLoading()
        view.onPause()
        (view.parent as? ViewGroup)?.removeView(view)
        view.webChromeClient = null
        view.setDownloadListener(null)
        view.destroy()
    }

    private fun sync(url: String?) {
        if (destroyed) return
        val u = url ?: view.url.orEmpty()
        page.url = u
        page.title = view.title.orEmpty().takeIf { it != u }.orEmpty()
        page.canGoBack = view.canGoBack()
        page.canGoForward = view.canGoForward()
        host.onPage(tabId, u, page.title)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return !request.isForMainFrame
            openExternally(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            page.loading = true
            sync(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            page.loading = false
            page.progress.floatValue = 1f
            sync(url)
            if (url != null) host.onVisited(url, page.title)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = sync(url)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Without this the whole app would be killed with the renderer.
            if (!destroyed) {
                destroyed = true
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                host.onRendererGone()
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            page.progress.floatValue = newProgress / 100f
        }

        override fun onReceivedTitle(view: WebView, title: String?) = sync(null)
    }

    /** tel:, mailto:, market:, intent: links go to an app that handles them, if there is one. */
    private fun openExternally(uri: Uri) {
        val intent = try {
            if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    // Never let a page target a specific (possibly private) component.
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } catch (_: Exception) {
            host.onNotice("Can't open that link")
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && BrowserUrls.isWeb(fallback)) load(fallback) else host.onNotice("No app for this link")
        } catch (_: SecurityException) {
            host.onNotice("Can't open that link")
        }
    }

    /** Hands a download to the system DownloadManager (into Downloads; no permission on API 29+). */
    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (!BrowserUrls.isWeb(url)) {
            host.onNotice("This download isn't supported")
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent ?: "")
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            host.onNotice("Downloading $name")
        } catch (_: Exception) {
            host.onNotice("Couldn't download $name")
        }
    }

    companion object {
        private const val THUMB_W = 96
        private const val THUMB_H_MAX = 128

        /**
         * A fresh engine for [tab]: restores its saved history or loads its address. Null if the
         * system WebView can't be created (e.g. it's being updated).
         */
        fun create(context: Context, tab: BrowserTab, textZoom: Int, host: BrowserHost): BrowserEngine? {
            val view = try {
                WebView(context)
            } catch (_: RuntimeException) {
                return null
            }
            return BrowserEngine(context, tab.id, view, host).apply {
                configure(textZoom)
                if (!restore(BrowserTabMemory.state(tab.id))) load(tab.url)
            }
        }
    }
}
