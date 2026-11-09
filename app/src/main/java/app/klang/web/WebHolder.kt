package app.klang.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** A saved or recently visited page. */
data class WebPage(val title: String, val url: String)

/**
 * The browser behind the web screen. Lives as long as the activity, so a film keeps its place
 * when you look at another screen and come back.
 *
 * Ad blocking, in layers:
 *  1. requests to ad and tracker servers are answered with nothing (EasyList + hosts lists);
 *  2. ad boxes that still show are hidden with the lists' element-hiding rules;
 *  3. pop-up windows never open (the address is offered on a chip instead);
 *  4. jumps to another site that the user did not tap, and links into other apps, are stopped.
 */
class WebHolder(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("klang", Activity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set
    /** Address of the last pop-up or redirect that was stopped, offered to open on purpose. */
    var stopped by mutableStateOf<String?>(null)
        private set
    /** A video in full screen (the page's own full-screen button). */
    var fullView by mutableStateOf<View?>(null)
        private set
    private var fullCallback: WebChromeClient.CustomViewCallback? = null

    var adblockOn by mutableStateOf(prefs.getBoolean("webAdblock", true))
        private set
    var allowedSites by mutableStateOf(prefs.getStringSet("webAllow", emptySet())!!.toSet())
        private set
    var history by mutableStateOf(loadPages("webHistory"))
        private set
    var bookmarks by mutableStateOf(loadPages("webBookmarks"))
        private set

    /** Page host, read by the request filter on WebView's own thread. */
    @Volatile private var pageHost: String? = null
    @Volatile private var filtering = true

    val blockingHere: Boolean get() = adblockOn && AdBlocker.siteOf(Uri.parse(url).host) !in allowedSites

    init {
        io.execute {
            AdBlocker.load(activity.applicationContext)
            AdBlocker.updateIfDue(activity.applicationContext)
        }
    }

    val view: WebView by lazy { makeView() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeView(): WebView =
        WebView(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(true)          // so pop-ups come to onCreateWindow, where they stop
                javaScriptCanOpenWindowsAutomatically = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = Client()
            webChromeClient = Chrome()
        }

    // ------------------------------------------------------------------ actions

    /** Words become a search; an address opens. */
    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        stopped = null
        view.loadUrl(target)
    }

    fun back() { if (view.canGoBack()) view.goBack() }
    fun forward() { if (view.canGoForward()) view.goForward() }
    fun reload() { if (url.isNotEmpty()) view.reload() }
    fun home() {
        view.stopLoading()
        view.loadUrl("about:blank")
        view.clearHistory()
        url = ""; title = ""; canBack = false; canForward = false; blockedCount = 0; stopped = null
    }

    fun openStopped() {
        val u = stopped ?: return
        stopped = null
        if (u.startsWith("http")) { filtering = true; view.loadUrl(u) }
    }
    fun dismissStopped() { stopped = null }

    fun toggleAdblock() {
        adblockOn = !adblockOn
        prefs.edit().putBoolean("webAdblock", adblockOn).apply()
        reload()
    }

    /** Lets ads through on this site only (some sites refuse to play with blockers). */
    fun toggleSite() {
        val site = AdBlocker.siteOf(Uri.parse(url).host)
        if (site.isEmpty()) return
        allowedSites = if (site in allowedSites) allowedSites - site else allowedSites + site
        prefs.edit().putStringSet("webAllow", allowedSites).apply()
        reload()
    }

    val isBookmarked: Boolean get() = bookmarks.any { it.url == url }
    fun toggleBookmark() {
        if (url.isEmpty()) return
        bookmarks = if (isBookmarked) bookmarks.filterNot { it.url == url } else listOf(WebPage(title.ifBlank { url }, url)) + bookmarks
        savePages("webBookmarks", bookmarks)
    }
    fun removeBookmark(p: WebPage) { bookmarks = bookmarks - p; savePages("webBookmarks", bookmarks) }
    fun clearHistory() { history = emptyList(); savePages("webHistory", history) }

    fun exitFullscreen() { fullCallback?.onCustomViewHidden() ; hideFull() }

    /** Leaving the screen: stop films and sound on the page. */
    fun pause() {
        if (fullView != null) return
        runCatching { view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null) }
        view.onPause()
    }
    fun resume() { view.onResume() }
    fun destroy() { io.shutdownNow(); runCatching { view.destroy() } }

    // ------------------------------------------------------------------ pages lists

    private fun loadPages(key: String): List<WebPage> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> WebPage(o.optString("t"), o.getString("u")) } }
    }.getOrDefault(emptyList())

    private fun savePages(key: String, list: List<WebPage>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun remember(p: WebPage) {
        if (!p.url.startsWith("http")) return
        history = (listOf(p) + history.filterNot { it.url == p.url }).take(20)
        savePages("webHistory", history)
    }

    private fun hideFull() {
        fullView = null
        fullCallback = null
    }

    private fun sync(v: WebView) {
        canBack = v.canGoBack(); canForward = v.canGoForward()
    }

    private fun countBlocked() = main.post { blockedCount++ }

    // ------------------------------------------------------------------ WebView callbacks

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (!filtering || req.isForMainFrame) return null
            val host = req.url.host ?: return null
            if (AdBlocker.siteOf(host) == AdBlocker.siteOf(pageHost)) return null
            if (!AdBlocker.isBlocked(host)) return null
            countBlocked()
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            val scheme = u.scheme ?: ""
            if (scheme != "http" && scheme != "https") {
                // intent:, market:, and other app links: almost always ad redirects here
                if (scheme != "about" && scheme != "data" && scheme != "blob") { stopped = u.toString(); countBlocked() }
                return scheme != "about"
            }
            if (!req.isForMainFrame) return false
            if (!filtering) return false
            if (AdBlocker.isBlocked(u.host)) { countBlocked(); return true }
            val cross = AdBlocker.siteOf(u.host) != AdBlocker.siteOf(pageHost)
            if (cross && !req.hasGesture() && !req.isRedirect && pageHost != null) {
                stopped = u.toString(); countBlocked()
                return true
            }
            return false
        }

        override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) {
            if (u == "about:blank") return
            val host = Uri.parse(u).host
            if (AdBlocker.siteOf(host) != AdBlocker.siteOf(pageHost)) blockedCount = 0
            pageHost = host
            filtering = adblockOn && AdBlocker.siteOf(host) !in allowedSites
            url = u
            sync(v)
        }

        override fun onPageCommitVisible(v: WebView, u: String) { injectCss(v, u) }

        override fun onPageFinished(v: WebView, u: String) {
            if (u == "about:blank") return
            injectCss(v, u)
            title = v.title.orEmpty()
            url = u
            sync(v)
            remember(WebPage(title.ifBlank { u }, u))
        }

        override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
            if (u != "about:blank") url = u
            sync(v)
        }
    }

    private fun injectCss(v: WebView, u: String) {
        if (!filtering || !AdBlocker.ready) return
        val host = Uri.parse(u).host
        io.execute {
            val css = AdBlocker.css(host)
            val js = "(function(){var id='klang-adblock';var s=document.getElementById(id);" +
                "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=" + JSONObject.quote(css) + ";})();"
            main.post { runCatching { v.evaluateJavascript(js, null) } }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(v: WebView, newProgress: Int) { progress = newProgress }
        override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }

        /** Pop-ups: let a throwaway view catch the address, then show it on a chip instead. */
        override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val catcher = WebView(activity)
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(w: WebView, req: WebResourceRequest): Boolean {
                    val target = req.url.toString()
                    if (!filtering) { main.post { view.loadUrl(target) } }
                    else if (!AdBlocker.isBlocked(req.url.host)) { stopped = target }
                    countBlocked()
                    main.post { runCatching { w.destroy() } }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullCallback?.onCustomViewHidden()
            fullView = view
            fullCallback = callback
        }

        override fun onHideCustomView() { hideFull() }
    }
}
