package app.klang.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A small relay on the phone that the TV plays from, at http://<phone>:<port>/<token>/...
 *
 * Why: film sites usually only answer the browser that opened them (Referer, cookies, user
 * agent), many TVs cannot open https over DLNA, and Chromecast needs CORS headers for HLS.
 * The relay fetches the stream with the browser's headers and passes it on; HLS playlists are
 * rewritten so every segment also goes through the relay.
 *
 * Only the stream being cast can be fetched: requests need the random token of this cast, and
 * only hosts that belong to that stream are relayed.
 */
class StreamProxy(private val context: Context) {
    class Headers(val referer: String?, val userAgent: String?, val cookie: String?)

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var token: String = ""
    @Volatile private var headers = Headers(null, null, null)
    private val hosts = java.util.Collections.synchronizedSet(HashSet<String>())

    val running: Boolean get() = server?.isClosed == false

    /** For the status line: how often the TV asked, and the last thing that went wrong upstream. */
    @Volatile var tvRequests = 0

    /** Tallest picture to give the TV (0 = whatever the site offers). One quality only: switching mid-film makes TVs stall. */
    @Volatile var maxHeight = 720
    /** Simple mode: the playlist as the site gives it, nothing fetched ahead (if the TV trips on the smart mode). */
    @Volatile var compat = false

    // ---- pieces of the film, fetched ahead so the TV never waits on the website
    private val order = java.util.Collections.synchronizedList(ArrayList<String>())
    private val cache = object : LinkedHashMap<String, Pair<ByteArray, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<ByteArray, String>>?) = size > 10
    }
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    private val ahead = Executors.newSingleThreadExecutor()
    @Volatile var lastProblem: String? = null

    /** Starts (or re-targets) the relay and returns the TV-facing address of [url]. */
    @Synchronized
    fun publish(url: String, h: Headers): String? {
        val ip = wifiAddress() ?: return null
        if (server == null || server!!.isClosed) {
            val s = ServerSocket(0, 50)
            server = s
            pool.execute { acceptLoop(s) }
        }
        token = randomToken()
        order.clear(); synchronized(cache) { cache.clear() }
        tvRequests = 0
        lastProblem = null
        ipNow = ip
        headers = h
        hosts.clear()
        URL(url).host?.let { hosts += it.lowercase() }
        return "http://$ip:${server!!.localPort}/$token/${encode(url)}${extensionOf(url)}"
    }

    @Synchronized
    fun stop() {
        order.clear(); synchronized(cache) { cache.clear() }
        runCatching { server?.close() }
        server = null
        token = ""
        hosts.clear()
    }

    private fun randomToken(): String {
        val b = ByteArray(12); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun encode(u: String) = Base64.encodeToString(u.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun decode(s: String) = runCatching { String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) }.getOrNull()

    /** TVs often look at the end of the address to know the kind of file. */
    private fun extensionOf(u: String): String {
        val path = runCatching { URI(u).path.orEmpty().lowercase() }.getOrDefault("")
        listOf(".m3u8", ".mp4", ".mpd", ".webm", ".ts", ".m4s", ".key", ".vtt").firstOrNull { path.endsWith(it) }?.let { return it }
        // pieces dressed up as pictures or pages: the TV should see them as video
        return if (listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".html", ".htm", ".txt", ".js", ".css").any { path.endsWith(it) }) ".ts" else ""
    }

    @Volatile private var ipNow: String = ""

    private fun proxied(u: String): String {
        val s = server ?: return u
        URL(u).host?.let { hosts += it.lowercase() }
        return "http://$ipNow:${s.localPort}/$token/${encode(u)}${extensionOf(u)}"
    }

    /** The phone's address on the Wi-Fi the TV is on (not mobile data, not a VPN). */
    fun wifiAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp: LinkProperties = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it.hostAddress }
        }
        // phone as a hotspot, or an unusual setup: any private IPv4 on wlan/ap/eth
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth") || it.name.startsWith("swlan")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            pool.execute { runCatching { handle(c) }; runCatching { c.close() } }
        }
    }

    // ------------------------------------------------------------------ one request

    private fun readHead(inp: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = inp.read()
            if (b < 0) break
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                if (line.isEmpty()) break
                lines += line; sb.setLength(0)
            } else sb.append(b.toChar())
            if (sb.length > 8192) break
            prev = b
        }
        return lines
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val inp = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val head = readHead(inp)
        if (head.isEmpty()) return
        val parts = head[0].split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: return
        val reqHeaders = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

        if (!c.inetAddress.isLoopbackAddress && c.inetAddress.hostAddress != ipNow) tvRequests++
        if (method == "OPTIONS") { status(out, 204, "No Content", cors() + listOf("Content-Length: 0")); return }
        val seg = path.trimStart('/').split('/', limit = 2)
        if (seg.size < 2 || seg[0] != token || token.isEmpty()) { status(out, 403, "Forbidden", listOf("Content-Length: 0")); return }
        val target = decode(seg[1].substringBefore('.').substringBefore('?')) ?: run { status(out, 400, "Bad Request", listOf("Content-Length: 0")); return }
        val host = runCatching { URL(target).host.lowercase() }.getOrNull()
        if (host == null || host !in hosts || !(target.startsWith("http://") || target.startsWith("https://"))) {
            status(out, 403, "Forbidden", listOf("Content-Length: 0")); return
        }

        if (!compat && method == "GET" && reqHeaders["range"] == null && order.contains(target)) {
            val piece = synchronized(cache) { cache[target] } ?: fetchPiece(target)
            if (piece != null) {
                synchronized(cache) { cache[target] = piece }
                prefetchAfter(target)
                status(out, 200, "OK", cors() + listOfNotNull(piece.second.takeIf { it.isNotEmpty() }?.let { "Content-Type: $it" }, "Content-Length: ${piece.first.size}"))
                out.write(piece.first); out.flush()
                return
            }
        }

        val conn = URL(target).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.requestMethod = if (method == "HEAD") "HEAD" else "GET"
        headers.referer?.let { conn.setRequestProperty("Referer", it); runCatching { conn.setRequestProperty("Origin", URL(it).let { r -> "${r.protocol}://${r.host}" }) } }
        headers.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        headers.cookie?.let { conn.setRequestProperty("Cookie", it) }
        reqHeaders["range"]?.let { conn.setRequestProperty("Range", it) }
        try {
            val code = conn.responseCode
            if (code >= 400) lastProblem = "site said $code"
            val type = conn.contentType.orEmpty()
            val finalUrl = conn.url.toString()
            val isPlaylist = type.contains("mpegurl", true) || finalUrl.substringBefore('?').endsWith(".m3u8", true) || target.substringBefore('?').endsWith(".m3u8", true)
            if (isPlaylist && code in 200..299) {
                val text = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                val body = rewrite(text, finalUrl).toByteArray()
                status(out, 200, "OK", cors() + listOf("Content-Type: application/vnd.apple.mpegurl", "Content-Length: ${body.size}", "Cache-Control: no-cache"))
                if (method != "HEAD") out.write(body)
                out.flush()
                return
            }
            // only small HLS pieces are read whole (never a full film file)
            val path = finalUrl.substringBefore('?').lowercase()
            val pieceExt = listOf(".ts", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".html", ".htm", ".txt", ".js", ".css")
            val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val segment = (pieceExt.any { path.endsWith(it) } || type.startsWith("image/") || type.contains("mp2t")) && len in -1L..(40L * 1024 * 1024)
            if (segment && code == 200 && method != "HEAD" && reqHeaders["range"] == null) {
                val bytes = conn.inputStream.use { it.readBytes() }
                val start = tsStart(bytes)
                val body = if (start > 0) bytes.copyOfRange(start, bytes.size) else bytes
                val type2 = if (start > 0 || looksTs(body)) "video/mp2t" else type
                status(out, 200, "OK", cors() + listOfNotNull(type2.takeIf { it.isNotEmpty() }?.let { "Content-Type: $it" }, "Content-Length: ${body.size}"))
                out.write(body); out.flush()
                return
            }
            val h = mutableListOf<String>()
            h += cors()
            if (type.isNotEmpty()) h += "Content-Type: $type"
            conn.getHeaderField("Content-Length")?.let { h += "Content-Length: $it" }
            conn.getHeaderField("Content-Range")?.let { h += "Content-Range: $it" }
            h += "Accept-Ranges: bytes"
            status(out, code, if (code == 206) "Partial Content" else "OK", h)
            if (method != "HEAD") {
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                stream?.use { it.copyTo(out, 64 * 1024) }
            }
            out.flush()
        } finally { conn.disconnect() }
    }

    private fun cors() = listOf(
        "Access-Control-Allow-Origin: *",
        "Access-Control-Allow-Methods: GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers: *",
        "Access-Control-Allow-Private-Network: true",
        "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges",
        "Connection: close",
    )

    private fun status(out: OutputStream, code: Int, text: String, headers: List<String>) {
        val sb = StringBuilder("HTTP/1.1 $code $text\r\n")
        headers.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    /** One piece of the film, cleaned (see [tsStart]). Null when the site refuses. */
    private fun fetchPiece(target: String): Pair<ByteArray, String>? = runCatching {
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        headers.referer?.let { conn.setRequestProperty("Referer", it); runCatching { conn.setRequestProperty("Origin", URL(it).let { r -> "${r.protocol}://${r.host}" }) } }
        headers.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        headers.cookie?.let { conn.setRequestProperty("Cookie", it) }
        try {
            if (conn.responseCode != 200) { lastProblem = "site said ${conn.responseCode}"; return null }
            val type = conn.contentType.orEmpty()
            val bytes = conn.inputStream.use { it.readBytes() }
            val start = tsStart(bytes)
            val body = if (start > 0) bytes.copyOfRange(start, bytes.size) else bytes
            body to (if (start > 0 || looksTs(body)) "video/mp2t" else if (isMp4(body)) "video/mp4" else type)
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Fetches the next few pieces while the TV plays this one. */
    private fun prefetchAfter(target: String) {
        val i = order.indexOf(target)
        if (i < 0) return
        val tok = token
        for (k in 1..3) {
            val next = order.getOrNull(i + k) ?: break
            if (synchronized(cache) { cache.containsKey(next) } || !inFlight.add(next)) continue
            ahead.execute {
                try {
                    if (tok == token) fetchPiece(next)?.let { p -> synchronized(cache) { cache[next] = p } }
                } finally { inFlight.remove(next) }
            }
        }
    }

    private fun looksTs(b: ByteArray) = b.size > 376 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte() && b[376] == 0x47.toByte()

    private fun isMp4(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val box = String(b, 4, 4, Charsets.ISO_8859_1)
        return box == "ftyp" || box == "moof" || box == "styp" || box == "sidx" || box == "moov"
    }

    /**
     * Where the real MPEG-TS starts when a segment wears something in front (a fake PNG/JPEG/GIF
     * header, or any padding added to stop other players); 0 when it does not.
     */
    private fun tsStart(b: ByteArray): Int {
        if (b.size < 800 || looksTs(b) || isMp4(b)) return 0
        val limit = minOf(b.size - 753, 256 * 1024)
        for (i in 1 until limit) {
            if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte() && b[i + 564] == 0x47.toByte() && b[i + 752] == 0x47.toByte()) return i
        }
        return 0
    }

    /** Reads a playlist with the site's headers (to learn its segment format). */
    fun fetchText(url: String, h: Headers): Pair<String, String>? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        h.referer?.let { c.setRequestProperty("Referer", it) }
        h.userAgent?.let { c.setRequestProperty("User-Agent", it) }
        h.cookie?.let { c.setRequestProperty("Cookie", it) }
        try { if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) to c.url.toString() else null } finally { c.disconnect() }
    }.getOrNull()

    private val uriAttr = Regex("URI=\"([^\"]+)\"")

    /** Every address in the playlist goes through the relay as well. */
    private val resRe = Regex("RESOLUTION=(\\d+)x(\\d+)")
    private val bwRe = Regex("[^-]BANDWIDTH=(\\d+)")

    /** A master playlist keeps one quality: the tallest within [maxHeight]. */
    private fun oneVariant(text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF") || maxHeight == 0 || compat) return text
        val lines = text.lines()
        val head = mutableListOf<String>()
        val variants = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) variants += l to lines[j]
                i = j + 1; continue
            }
            if (!l.startsWith("#EXT-X-I-FRAME-STREAM-INF")) head += l
            i++
        }
        if (variants.size <= 1) return text
        fun height(inf: String) = resRe.find(inf)?.groupValues?.get(2)?.toIntOrNull()
        fun bw(inf: String) = bwRe.find(" $inf")?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val capBw = when { maxHeight <= 480 -> 1_600_000L; maxHeight <= 720 -> 3_500_000L; else -> 8_000_000L }
        val pick = if (variants.all { height(it.first) != null }) {
            variants.filter { height(it.first)!! <= maxHeight }.maxByOrNull { height(it.first)!! * 100_000_000L + bw(it.first) }
                ?: variants.minByOrNull { height(it.first)!! }
        } else {
            variants.filter { bw(it.first) <= capBw }.maxByOrNull { bw(it.first) } ?: variants.minByOrNull { bw(it.first) }
        } ?: return text
        return (head.filter { it.isNotBlank() } + pick.first + pick.second).joinToString("\n") + "\n"
    }

    private fun rewrite(text0: String, base: String): String {
        val text = oneVariant(text0)
        val b = URI(base)
        fun abs(u: String) = runCatching { b.resolve(u.trim()).toString() }.getOrDefault(u)
        val media = !text.contains("#EXT-X-STREAM-INF")
        return text.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"" + proxied(abs(m.groupValues[1])) + "\"" }
                else -> abs(line).let { u -> if (media && !order.contains(u)) order += u; proxied(u) }
            }
        }
    }
}
