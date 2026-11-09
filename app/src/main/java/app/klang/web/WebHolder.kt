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

/** A video stream found on the page, which can be sent to a TV. */
data class WebMedia(val url: String, val mime: String, val referer: String?)

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
    /** Video streams seen on this page, best first (HLS playlists, then files). */
    var media by mutableStateOf<List<WebMedia>>(emptyList())
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

    /**
     * Private mode: a separate browser profile (own cookies, logins, storage and cache) that keeps
     * no history and is deleted when private mode is switched off or the app closes. The normal
     * profile's logins are not touched. Phones whose WebView is too old for profiles fall back to
     * no cookies while private.
     */
    var privateMode by mutableStateOf(prefs.getBoolean("webPrivate", false))
        private set
    private val profiles = runCatching {
        androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)
    private var normalView: WebView? = null
    private var privateView: WebView? = null

    init {
        // a private profile left behind by a crash or a killed app goes now
        if (profiles) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        io.execute {
            AdBlocker.load(activity.applicationContext)
            AdBlocker.updateIfDue(activity.applicationContext)
        }
    }

    /** The browser in use: the private one while private mode is on. */
    val view: WebView
        get() = if (privateMode) privateView ?: makeView(true).also { privateView = it }
        else normalView ?: makeView(false).also { normalView = it }

    /** Switches private mode. Leaving it deletes everything the private profile held. */
    fun togglePrivate() {
        if (fullView != null) exitFullscreen()
        if (privateMode) {
            privateView?.let { v -> runCatching { v.stopLoading(); (v.parent as? android.view.ViewGroup)?.removeView(v); v.destroy() } }
            privateView = null
            wipePrivate()
            privateMode = false
        } else {
            normalView?.onPause()
            privateMode = true
        }
        prefs.edit().putBoolean("webPrivate", privateMode).apply()
        if (!profiles) CookieManager.getInstance().setAcceptCookie(!privateMode)
        // show the state of the browser now in front
        val v = view
        v.onResume()
        url = v.url?.takeIf { it != "about:blank" }.orEmpty()
        title = v.title.orEmpty()
        pageHost = Uri.parse(url).host
        blockedCount = 0; stopped = null; media = emptyList(); progress = 100
        sync(v)
    }

    private fun wipePrivate() {
        if (profiles) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeView(isPrivate: Boolean): WebView =
        WebView(activity).apply {
            if (isPrivate && profiles) runCatching {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                androidx.webkit.WebViewCompat.setProfile(this, PRIVATE_PROFILE)
            }
            if (isPrivate) settings.cacheMode = WebSettings.LOAD_NO_CACHE.takeIf { !profiles } ?: WebSettings.LOAD_DEFAULT
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
            CookieManager.getInstance().setAcceptCookie(!(isPrivate && !profiles))
            webViewClient = Client()
            // banner picking: while on, a tap chooses the thing under the finger instead of clicking it
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { v, e ->
                if (!picking) return@setOnTouchListener false
                if (e.action == android.view.MotionEvent.ACTION_UP) {
                    @Suppress("DEPRECATION")
                    val sc = (v as WebView).scale.takeIf { it > 0f } ?: v.resources.displayMetrics.density
                    pickAt(e.x / sc, e.y / sc)
                }
                true
            }
            webChromeClient = Chrome()
            // in every frame (players usually sit in an iframe): long films carry on where they were left
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, RESUME_JS, setOf("*")) }
                runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, PIP_JS, setOf("*")) }
            }
        }

    private companion object {
        const val PRIVATE_PROFILE = "klang-private"

        /** Finds the banner under a point, outlines it in red and describes it with a CSS selector. */
        const val PICK_JS = """(function(){
 if (window.__klangPick) return;
 function esc(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, '\\$&'); }
 function area(e) { var r = e.getBoundingClientRect(); return r.width * r.height; }
 function stable(k) { return !/\d{3,}/.test(k) && k.indexOf('klang') < 0; }
 function sel(e) {
  if (e.id && stable(e.id)) return '#' + esc(e.id);
  var tag = e.tagName.toLowerCase();
  if (tag === 'img' && e.getAttribute('src')) { var f = e.getAttribute('src').split('?')[0].split('/').pop(); if (f) return 'img[src*="' + f.replace(/"/g, '') + '"]'; }
  if (tag === 'iframe' && e.src) { try { return 'iframe[src*="' + new URL(e.src).host + '"]'; } catch (x) {} }
  if (tag === 'a' && e.getAttribute('href') && e.getAttribute('href').length < 200 && e.getAttribute('href').indexOf('"') < 0) return 'a[href="' + e.getAttribute('href') + '"]';
  var path = [], c = e;
  while (c && c !== document.body && path.length < 5) {
   if (c.id && stable(c.id)) { path.unshift('#' + esc(c.id)); break; }
   var s = c.tagName.toLowerCase();
   var cls = [].slice.call(c.classList).filter(stable).slice(0, 2);
   if (cls.length) s += '.' + cls.map(esc).join('.');
   var p = c.parentElement;
   if (p) { var same = [].filter.call(p.children, function(k){ return k.tagName === c.tagName; }); if (same.length > 1) s += ':nth-of-type(' + (same.indexOf(c) + 1) + ')'; }
   path.unshift(s); c = p;
  }
  return path.join(' > ');
 }
 function show(t) {
  document.querySelectorAll('.klang-pick').forEach(function(e){ e.classList.remove('klang-pick'); });
  if (!document.getElementById('klang-pick-css')) {
   var st = document.createElement('style'); st.id = 'klang-pick-css';
   st.textContent = '.klang-pick{outline:3px solid #F01E14!important;outline-offset:-3px!important;box-shadow:inset 0 0 0 2000px rgba(240,30,20,.28)!important}';
   (document.head || document.documentElement).appendChild(st);
  }
  t.classList.add('klang-pick'); window.__klangPicked = t;
  var r = t.getBoundingClientRect();
  return JSON.stringify({ sel: sel(t), w: Math.round(r.width), h: Math.round(r.height), tag: t.tagName.toLowerCase() });
 }
 window.__klangPick = function(x, y) {
  var t = document.elementFromPoint(x, y); if (!t || t === document.body || t === document.documentElement) return null;
  for (var i = 0; i < 3 && t.parentElement && t.parentElement !== document.body; i++) {
   var pr = t.parentElement.getBoundingClientRect(), tr = t.getBoundingClientRect();
   if (pr.height > tr.height * 1.6 + 8 || pr.width > tr.width * 1.6 + 8) break;
   t = t.parentElement;
  }
  var a = t.closest ? t.closest('a') : null;
  if (a && area(a) < area(document.body) * 0.5) t = a;
  return show(t);
 };
 window.__klangPickUp = function() {
  var t = window.__klangPicked; if (!t || !t.parentElement || t.parentElement === document.body) return null;
  return show(t.parentElement);
 };
})()"""

        /**
         * Floating window: the film alone. The frame holding the playing video blows it up to fill
         * the window and hides the rest of its page; a frame that holds that frame does the same
         * with the frame, up to the top. Undone when the window opens up again.
         */
        const val PIP_JS = """(function(){
 if (window.__klangPip) return;
 var marked = [], kids = {}, seq = 0, pick = null;
 function css(on) {
  var d = document, s = d.getElementById('klang-pip-css');
  if (on && !s) {
   s = d.createElement('style'); s.id = 'klang-pip-css';
   s.textContent = 'html.klang-pip,html.klang-pip body{background:#000!important;overflow:hidden!important}' +
    'html.klang-pip body *{visibility:hidden!important}' +
    'html.klang-pip .klang-pip-el,html.klang-pip .klang-pip-el *{visibility:visible!important}' +
    'html.klang-pip .klang-pip-up{transform:none!important;filter:none!important;backdrop-filter:none!important;perspective:none!important;contain:none!important;will-change:auto!important;clip-path:none!important;mask:none!important;opacity:1!important}' +
    'html.klang-pip .klang-pip-el{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;margin:0!important;padding:0!important;border:0!important;transform:none!important;opacity:1!important;display:block!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}';
   (d.head || d.documentElement).appendChild(s);
  }
  if (on) d.documentElement.classList.add('klang-pip'); else d.documentElement.classList.remove('klang-pip');
 }
 function mark(el) {
  el.classList.add('klang-pip-el'); marked.push(el);
  for (var p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) { p.classList.add('klang-pip-up'); marked.push(p); }
 }
 function clear() { marked.forEach(function(el){ el.classList.remove('klang-pip-el'); el.classList.remove('klang-pip-up'); }); marked = []; css(false); }
 // the film: playing with sound beats playing muted (ads, previews) beats loaded beats big
 function score(v) {
  var r = v.getBoundingClientRect(), a = Math.max(0, r.width) * Math.max(0, r.height);
  var s = Math.min(a, 4e6);
  if (v.readyState >= 2) s += 1e7;
  if (v.currentTime > 0) s += 1e7;
  if (!v.paused) s += 1e8;
  if (!v.paused && !v.muted && v.volume > 0) s += 1e9;
  if (isFinite(v.duration) && v.duration > 600) s += 5e7;
  return s;
 }
 function local() {
  var b = null, bs = 0;
  [].slice.call(document.querySelectorAll('video')).forEach(function(v){ var s = score(v); if (s > bs) { bs = s; b = v; } });
  return { el: b, s: bs };
 }
 function frameOf(src) { var fs = document.querySelectorAll('iframe,frame'); for (var i = 0; i < fs.length; i++) if (fs[i].contentWindow === src) return fs[i]; return null; }
 // ask this page and every frame below it where the film is; answer with the best score
 function ask(done) {
  var id = ++seq, me = local(), best = { s: me.s, where: me.el ? 'here' : null, el: me.el };
  kids[id] = function(src, s) { if (s > best.s) { var f = frameOf(src); if (f) best = { s: s, where: 'frame', el: f, win: src }; } };
  var n = window.frames.length;
  for (var i = 0; i < n; i++) try { window.frames[i].postMessage({ klangPipAsk: id }, '*'); } catch (e) {}
  setTimeout(function(){ delete kids[id]; pick = best; done(best); }, n ? 350 : 0);
 }
 function go() {
  clear();
  var p = pick; if (!p || !p.el) return;
  css(true); mark(p.el);
  if (p.where === 'frame') try { p.win.postMessage({ klangPipGo: 1 }, '*'); } catch (e) {}
 }
 function off() {
  clear(); pick = null;
  for (var i = 0; i < window.frames.length; i++) try { window.frames[i].postMessage({ klangPipOff: 1 }, '*'); } catch (e) {}
 }
 window.addEventListener('message', function(e){
  var d = e.data; if (!d || typeof d !== 'object') return;
  if (d.klangPipAsk) ask(function(b){ try { e.source.postMessage({ klangPipScore: d.klangPipAsk, s: b.s }, '*'); } catch (x) {} });
  else if (d.klangPipScore && kids[d.klangPipScore]) kids[d.klangPipScore](e.source, d.s);
  else if (d.klangPipGo) go();
  else if (d.klangPipOff) off();
 });
 window.__klangPip = function(on) { if (on) ask(function(){ go(); }); else off(); };
})();"""
        const val RESUME_JS = """(function(){
 if (window.__klangPos) return; window.__klangPos = 1;
 var K = 'klang-pos:' + location.href.split('#')[0];
 function hook(v) {
  if (v.__klang) return; v.__klang = 1;
  v.addEventListener('loadedmetadata', function() {
   try { var t = parseFloat(localStorage.getItem(K) || '0');
    if (t > 30 && v.duration && t < v.duration - 30) v.currentTime = t; } catch (e) {}
  });
  var last = 0;
  v.addEventListener('timeupdate', function() {
   var n = Date.now(); if (n - last < 5000) return; last = n;
   try { if (v.duration && v.duration > 300 && isFinite(v.duration)) {
    if (v.currentTime > v.duration - 30) localStorage.removeItem(K); else localStorage.setItem(K, String(v.currentTime)); } } catch (e) {}
  });
 }
 function scan() { document.querySelectorAll('video').forEach(hook); }
 try { new MutationObserver(scan).observe(document.documentElement || document, { childList: true, subtree: true }); } catch (e) {}
 document.addEventListener('DOMContentLoaded', scan);
})();"""
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
    fun pause(force: Boolean = false) {
        if (fullView != null && !force) return
        runCatching { view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null) }
        view.onPause()
    }
    fun resume() { view.onResume() }
    fun destroy() {
        io.shutdownNow()
        runCatching { normalView?.destroy() }
        runCatching { privateView?.destroy() }
        privateView = null
        wipePrivate()   // closing the app ends the private session
    }
    fun useCaster(): app.klang.cast.Caster = caster

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
        if (privateMode || !p.url.startsWith("http")) return
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

    private val mediaExt = Regex("\\.(m3u8|mp4|mpd|webm|mkv|mov)($|[?#])", RegexOption.IGNORE_CASE)

    private fun mimeOf(u: String): String? {
        val path = u.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || u.contains(".m3u8", true) -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            else -> null
        }
    }

    /** Notes a video address (from the page's requests or its video tags). */
    private fun noteMedia(u: String, referer: String?) {
        if (!u.startsWith("http")) return
        val mime = mimeOf(u) ?: return
        // HLS segments and sub-playlists come after the master playlist: keep the first of each kind
        main.post {
            if (media.any { it.url == u }) return@post
            if (mime == "application/x-mpegURL" && media.count { it.mime == mime } >= 3) return@post
            val m = WebMedia(u, mime, referer)
            media = (media + m).sortedBy { if (it.mime == "application/x-mpegURL") 0 else if (it.mime == "video/mp4") 1 else 2 }.take(8)
        }
    }

    // ------------------------------------------------------------------ hide a banner by hand

    /** Choosing a banner to hide: the next tap on the page picks what is under the finger. */
    var picking by mutableStateOf(false)
        private set
    /** What was picked: a CSS selector and a short description, or null. */
    var picked by mutableStateOf<Pair<String, String>?>(null)
        private set

    private fun site(): String = AdBlocker.siteOf(Uri.parse(url).host)
    private fun rulesFor(site: String): Set<String> = prefs.getStringSet("webHide:$site", emptySet())!!
    val hiddenHere: Int get() = rulesFor(site()).size

    private fun userCss(host: String?): String {
        val rules = rulesFor(AdBlocker.siteOf(host))
        return if (rules.isEmpty()) "" else rules.joinToString("\n") { "$it{display:none!important}" }
    }

    fun startPicking() { picked = null; picking = true }

    fun cancelPicking() {
        picking = false; picked = null
        runCatching { view.evaluateJavascript("document.querySelectorAll('.klang-pick').forEach(function(e){e.classList.remove('klang-pick')})", null) }
    }

    private fun pickResult(json: String?) {
        val o = runCatching { JSONObject(JSONArray("[${json ?: "null"}]").getString(0)) }.getOrNull() ?: return
        val sel = o.optString("sel").takeIf { it.isNotBlank() } ?: return
        picked = sel to "${o.optString("tag")} · ${o.optInt("w")}×${o.optInt("h")}"
    }

    private fun pickAt(x: Float, y: Float) {
        runCatching { view.evaluateJavascript("$PICK_JS;window.__klangPick($x,$y)") { pickResult(it) } }
    }

    /** The picked thing was only part of the banner: take the box around it. */
    fun pickBigger() {
        runCatching { view.evaluateJavascript("$PICK_JS;window.__klangPickUp()") { pickResult(it) } }
    }

    /** Hides the picked banner on this site from now on. */
    fun hidePicked() {
        val sel = picked?.first ?: return
        val site = site()
        if (site.isNotEmpty()) prefs.edit().putStringSet("webHide:$site", rulesFor(site) + sel).apply()
        picking = false; picked = null
        runCatching {
            view.evaluateJavascript("document.querySelectorAll('.klang-pick').forEach(function(e){e.classList.remove('klang-pick')});" +
                "(function(){var s=document.createElement('style');s.textContent=" + JSONObject.quote("$sel{display:none!important}") + ";(document.head||document.documentElement).appendChild(s);})()", null)
        }
        blockedCount++
    }

    /** Shows again everything hidden by hand on this site. */
    fun unhideAllHere() {
        val site = site()
        if (site.isEmpty()) return
        prefs.edit().remove("webHide:$site").apply()
        picking = false; picked = null
        reload()
    }

    /** Small floating window: show only the film on the page (or the whole page again). */
    fun focusFilm(on: Boolean) {
        runCatching { view.evaluateJavascript("$PIP_JS;window.__klangPip && window.__klangPip($on);", null) }
    }

    /** Pauses every video and sound on the page (e.g. when the film goes to the TV). */
    fun pauseMedia() {
        runCatching { view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null) }
    }

    /** Sends films to a TV. Made when first needed. */
    val caster: app.klang.cast.Caster get() = app.klang.cast.Caster.get(activity)
    val hasCaster: Boolean get() = app.klang.cast.Caster.peek() != null

    /** Looks again for videos, e.g. after the film has started. */
    fun rescan() { scanVideoTags(view) }

    /** Looks for <video> tags with a real address (not blob:). */
    private fun scanVideoTags(v: WebView) {
        val js = "(function(){var o=[];document.querySelectorAll('video,video source').forEach(function(e){var s=e.currentSrc||e.src;if(s&&s.indexOf('http')==0)o.push(s)});return JSON.stringify(o)})()"
        runCatching {
            v.evaluateJavascript(js) { res ->
                runCatching {
                    val arr = JSONArray(JSONArray("[$res]").getString(0))
                    for (i in 0 until arr.length()) noteMedia(arr.getString(i), url)
                }
            }
        }
    }

    /** What to send to the TV, with the headers the site expects. */
    fun castMedia(): app.klang.cast.CastMedia? {
        val m = media.firstOrNull() ?: return null
        val cookie = runCatching { CookieManager.getInstance().getCookie(m.url) }.getOrNull()
        return app.klang.cast.CastMedia(
            m.url, title.ifBlank { "Klang Audio" }, m.mime,
            app.klang.cast.StreamProxy.Headers(m.referer ?: url, view.settings.userAgentString, cookie)
        )
    }

    // ------------------------------------------------------------------ WebView callbacks

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
            val u = req.url.toString()
            if (!req.isForMainFrame && mediaExt.containsMatchIn(u) && !(filtering && AdBlocker.isBlocked(req.url.host))) {
                noteMedia(u, req.requestHeaders["Referer"] ?: req.requestHeaders["referer"])
            }
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
            if (v !== view) return
            if (u == "about:blank") return
            val host = Uri.parse(u).host
            if (AdBlocker.siteOf(host) != AdBlocker.siteOf(pageHost)) blockedCount = 0
            media = emptyList()
            pageHost = host
            filtering = adblockOn && AdBlocker.siteOf(host) !in allowedSites
            url = u
            sync(v)
        }

        override fun onPageCommitVisible(v: WebView, u: String) { injectCss(v, u) }

        override fun onPageFinished(v: WebView, u: String) {
            if (v !== view) return
            if (u == "about:blank") return
            injectCss(v, u)
            scanVideoTags(v)
            title = v.title.orEmpty()
            url = u
            sync(v)
            remember(WebPage(title.ifBlank { u }, u))
        }

        override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
            if (v !== view) return
            if (u != "about:blank") url = u
            sync(v)
        }
    }

    private fun injectCss(v: WebView, u: String) {
        if (!filtering) return
        val host = Uri.parse(u).host
        io.execute {
            val css = (if (AdBlocker.ready) AdBlocker.css(host) else "") + "\n" + userCss(host)
            val js = "(function(){var id='klang-adblock';var s=document.getElementById(id);" +
                "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=" + JSONObject.quote(css) + ";})();"
            main.post { runCatching { v.evaluateJavascript(js, null) } }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(v: WebView, newProgress: Int) { if (v === view) progress = newProgress }
        override fun onReceivedTitle(v: WebView, t: String?) { if (v === view && !t.isNullOrBlank()) title = t }

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
