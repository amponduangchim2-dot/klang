package app.klang.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions as GCastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import java.util.concurrent.Executors

/** Google Cast setup: the standard receiver on the TV plays the stream itself. */
class CastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): GCastOptions = GCastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** A TV the phone can send a film to. */
data class Tv(val id: String, val name: String, val kind: Kind, internal val route: MediaRouter.RouteInfo? = null, internal val dlna: Dlna.Renderer? = null) {
    enum class Kind { GOOGLE_CAST, DLNA }
}

/** What is being cast: a video address with the headers the site wants. */
data class CastMedia(val url: String, val title: String, val mime: String, val headers: StreamProxy.Headers)

/**
 * Finds TVs (Google Cast and DLNA) and sends a video to one of them through [StreamProxy].
 * State is Compose state, read by the web screen.
 */
class Caster(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val proxy = StreamProxy(app)
    private val cast: CastContext? = runCatching { CastContext.getSharedInstance(app) }.getOrNull()   // no Play services: DLNA only
    private val router = MediaRouter.getInstance(app)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()
    private var wifiLock: WifiManager.WifiLock? = null

    var tvs by mutableStateOf<List<Tv>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var current by mutableStateOf<Tv?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var dlnaFound: List<Tv> = emptyList()
    private var pending: CastMedia? = null

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { pending?.let { loadOnCast(s, it) } }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { pending?.let { loadOnCast(s, it) } }
        override fun onSessionStartFailed(s: CastSession, error: Int) { message = "Couldn't connect to the TV"; current = null }
        override fun onSessionEnded(s: CastSession, error: Int) { if (current?.kind == Tv.Kind.GOOGLE_CAST) finish() }
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    init {
        cast?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    private fun refresh() {
        val google = router.routes.filter { !it.isDefaultOrBluetooth && it.matchesSelector(selector) && it.isEnabled }
            .map { Tv("gc:" + it.id, it.name, Tv.Kind.GOOGLE_CAST, route = it) }
        tvs = google + dlnaFound
    }

    /** Looks for TVs while the picker is open. */
    fun startScan() {
        if (cast != null) router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        scanning = true
        io.execute {
            val found = Dlna.discover(app).map { Tv("dlna:" + it.controlUrl, it.name, Tv.Kind.DLNA, dlna = it) }
            main.post { dlnaFound = found; refresh(); scanning = false }
        }
    }

    fun stopScan() {
        if (cast != null) router.removeCallback(routeCallback)
        scanning = false
    }

    /** Sends [media] to [tv]. */
    fun castTo(tv: Tv, media: CastMedia) {
        message = null
        current = tv
        pending = media
        holdWifi(true)
        when (tv.kind) {
            Tv.Kind.GOOGLE_CAST -> {
                val s = cast?.sessionManager?.currentCastSession
                if (s != null && s.isConnected) loadOnCast(s, media) else tv.route?.let { router.selectRoute(it) }
            }
            Tv.Kind.DLNA -> io.execute {
                val url = runCatching { proxy.publish(media.url, media.headers) }.getOrNull()
                val code = if (url != null) relayWorks(url) else -1
                val ok = url != null && code in 200..299 && Dlna.play(tv.dlna!!, url, media.title, dlnaMime(media.mime))
                main.post {
                    playing = ok
                    if (!ok) {
                        message = when {
                            url == null -> "Connect the phone to the same Wi-Fi as the TV."
                            code !in 200..299 -> "The site refused to hand the video over (error $code)."
                            else -> "The TV didn't accept this video. Try Google Cast or mirroring the screen."
                        }
                        finish()
                    }
                }
            }
        }
    }

    private fun dlnaMime(m: String) = when {
        m.contains("mpegurl", true) -> "application/vnd.apple.mpegurl"
        else -> m
    }

    /** HLS on Google Cast: the TV must be told whether the pieces are MPEG-TS or fragmented MP4. */
    private fun hlsIsFmp4(media: CastMedia): Boolean {
        var (text, base) = proxy.fetchText(media.url, media.headers) ?: return false
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val u = runCatching { java.net.URI(base).resolve(variant).toString() }.getOrNull() ?: return false
            val v = proxy.fetchText(u, media.headers) ?: return false
            text = v.first
        }
        return text.contains("#EXT-X-MAP") || text.lineSequence().any { !it.startsWith("#") && (it.contains(".m4s") || it.substringBefore('?').endsWith(".mp4")) }
    }

    /** The relay answers and the site gives the video: checked on the phone before the TV tries. */
    private fun relayWorks(url: String): Int = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15_000
        c.setRequestProperty("Range", "bytes=0-1023")
        try { c.responseCode.also { if (it in 200..299) c.inputStream.use { s -> s.read(ByteArray(1024)) } } } finally { c.disconnect() }
    }.getOrDefault(-1)

    // ---- what the TV reports, and a second try with the site's own address
    private var lastMedia: CastMedia? = null
    private var lastFmp4 = false
    private var triedDirect = false
    private var lastTvError: Int? = null

    private fun diagnosis(): String {
        val parts = mutableListOf<String>()
        lastTvError?.let { parts += "tv error $it" }
        parts += "tv asked the phone ${proxy.tvRequests}×"
        proxy.lastProblem?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private val statusCallback = object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
        override fun onMediaError(error: com.google.android.gms.cast.MediaError) {
            lastTvError = error.detailedErrorCode
        }

        override fun onStatusUpdated() {
            val st = cast?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus ?: return
            playing = st.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ||
                st.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING
            if (playing) message = null
            if (st.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE &&
                st.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR) {
                val m = lastMedia
                val s = cast?.sessionManager?.currentCastSession
                if (!triedDirect && m != null && s != null) {
                    // through the phone did not work: let the TV fetch the site's address itself
                    triedDirect = true
                    message = "trying the direct link…"
                    sendToTv(s, m.url, m, lastFmp4)
                } else {
                    message = "The TV could not play this stream (${diagnosis()}). Try another server on the site, or mirror the screen."
                }
            }
        }
    }

    private fun sendToTv(s: CastSession, url: String, media: CastMedia, fmp4: Boolean) {
        val hls = media.mime.contains("mpegurl", true)
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, media.title) }
        val b = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (hls) "application/x-mpegurl" else media.mime)
            .setMetadata(meta)
        if (hls) {
            b.setHlsSegmentFormat(if (fmp4) com.google.android.gms.cast.HlsSegmentFormat.FMP4 else com.google.android.gms.cast.HlsSegmentFormat.TS)
            b.setHlsVideoSegmentFormat(if (fmp4) com.google.android.gms.cast.HlsVideoSegmentFormat.FMP4 else com.google.android.gms.cast.HlsVideoSegmentFormat.MPEG2_TS)
        }
        val client = s.remoteMediaClient ?: run { message = "The TV isn't ready. Try again."; return }
        client.unregisterCallback(statusCallback)
        client.registerCallback(statusCallback)
        client.load(MediaLoadRequestData.Builder().setMediaInfo(b.build()).setAutoplay(true).build())
            .setResultCallback { r ->
                main.post {
                    if (r.status.isSuccess) playing = true
                    else message = "The TV couldn't open this video (code ${r.status.statusCode} · ${diagnosis()}). Try mirroring the screen."
                }
            }
    }

    private fun loadOnCast(s: CastSession, media: CastMedia) {
        pending = null
        message = "sending to the TV…"
        io.execute {
            val url = runCatching { proxy.publish(media.url, media.headers) }.getOrNull()
            if (url == null) { main.post { message = "Connect the phone to the same Wi-Fi as the TV."; stop() }; return@execute }
            val code = relayWorks(url)
            if (code !in 200..299) {
                main.post {
                    message = if (code < 0) "The phone couldn't reach the video. Check the Wi-Fi and try again."
                    else "The site refused to hand the video over (error $code). Start the film on the page again, then cast."
                    stop()
                }
                return@execute
            }
            val hls = media.mime.contains("mpegurl", true)
            val fmp4 = hls && hlsIsFmp4(media)
            main.post {
                lastMedia = media; lastFmp4 = fmp4; triedDirect = false; lastTvError = null
                sendToTv(s, url, media, fmp4)
            }
        }
    }

    fun togglePlay() {
        val tv = current ?: return
        when (tv.kind) {
            Tv.Kind.GOOGLE_CAST -> cast?.sessionManager?.currentCastSession?.remoteMediaClient?.let { it.togglePlayback(); playing = !playing }
            Tv.Kind.DLNA -> io.execute { val p = playing; val ok = if (p) Dlna.pause(tv.dlna!!) else Dlna.resume(tv.dlna!!); main.post { if (ok) playing = !p } }
        }
    }

    fun stop() {
        val tv = current ?: return
        when (tv.kind) {
            Tv.Kind.GOOGLE_CAST -> cast?.sessionManager?.endCurrentSession(true)
            Tv.Kind.DLNA -> io.execute { Dlna.stop(tv.dlna!!) }
        }
        finish()
    }

    fun dismissMessage() { message = null }

    private fun finish() {
        current = null
        playing = false
        pending = null
        holdWifi(false)
        io.execute { proxy.stop() }
    }

    private fun holdWifi(on: Boolean) {
        if (on) {
            if (wifiLock == null) {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "klang-cast").apply { setReferenceCounted(false); acquire() }
            }
        } else { runCatching { wifiLock?.release() }; wifiLock = null }
    }

    fun release() {
        stopScan()
        cast?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        if (current != null) stop()
        io.shutdown()
    }
}
