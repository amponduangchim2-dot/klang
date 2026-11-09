package app.klang.cast

import android.content.Context
import android.net.wifi.WifiManager
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA / UPnP media renderers on the Wi-Fi (most Samsung, LG, Sony and Philips TVs):
 * found with SSDP, driven with the AVTransport service (set the address, play, pause, stop).
 */
object Dlna {
    data class Renderer(val name: String, val controlUrl: String, val location: String)

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Looks for TVs for about three seconds. Call off the main thread. */
    fun discover(context: Context, seconds: Int = 3): List<Renderer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("klang-dlna").apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, String>()   // location -> nothing yet
        try {
            MulticastSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(java.net.InetSocketAddress(0))
                sock.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $AVT\r\n\r\n").toByteArray()
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val until = System.currentTimeMillis() + seconds * 1000L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val text = String(pkt.data, 0, pkt.length)
                    val loc = text.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }?.substringAfter(':')?.trim()
                    if (loc != null && loc.startsWith("http")) found[loc] = loc
                }
            }
        } catch (_: Exception) {
        } finally { runCatching { lock.release() } }
        return found.keys.mapNotNull { describe(it) }.distinctBy { it.controlUrl }
    }

    private fun describe(location: String): Renderer? = runCatching {
        val xml = get(location) ?: return null
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml))
        val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim() ?: "TV"
        val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val s = services.item(i) as Element
            val type = s.getElementsByTagName("serviceType").item(0)?.textContent.orEmpty()
            if (type.contains("AVTransport")) {
                val ctl = s.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
                return Renderer(name, URL(URL(base), ctl).toString(), location)
            }
        }
        null
    }.getOrNull()

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(r: Renderer, action: String, args: String): Boolean = call(r, action, args) != null

    /** Sends one AVTransport action; the TV's answer, or null when it refused. */
    private fun call(r: Renderer, action: String, args: String): String? = runCatching {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$AVT"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        val c = URL(r.controlUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$AVT#$action\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        try { if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) else null } finally { c.disconnect() }
    }.getOrNull()

    /** Sends [url] to the TV and starts it. Call off the main thread. */
    fun play(r: Renderer, url: String, title: String, mime: String): Boolean {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${esc(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${esc(url)}</res></item></DIDL-Lite>"""
        soap(r, "Stop", "")
        val ok = soap(r, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
        return ok && soap(r, "Play", "<Speed>1</Speed>")
    }

    fun pause(r: Renderer) = soap(r, "Pause", "")
    fun resume(r: Renderer) = soap(r, "Play", "<Speed>1</Speed>")
    fun stop(r: Renderer) = soap(r, "Stop", "")

    /** Jumps to [ms] in the film. */
    fun seek(r: Renderer, ms: Long) = soap(r, "Seek", "<Unit>REL_TIME</Unit><Target>${clock(ms)}</Target>")

    /** Where the TV is in the film and how long it is, in ms. Null when the TV doesn't say. */
    fun position(r: Renderer): Pair<Long, Long>? {
        val x = call(r, "GetPositionInfo", "") ?: return null
        fun tag(n: String) = Regex("<(?:\\w+:)?$n>([^<]*)</").find(x)?.groupValues?.get(1)
        val at = parseClock(tag("RelTime")) ?: return null
        return at to (parseClock(tag("TrackDuration")) ?: 0L)
    }

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
    }

    private fun parseClock(s: String?): Long? {
        val p = s?.trim()?.substringBefore('.')?.split(':') ?: return null
        if (p.size != 3) return null
        val h = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull() ?: return null
        val sec = p[2].toLongOrNull() ?: return null
        return ((h * 60 + m) * 60 + sec) * 1000
    }
}
