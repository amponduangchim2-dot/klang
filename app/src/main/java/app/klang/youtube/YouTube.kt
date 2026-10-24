package app.klang.youtube

import android.net.Uri
import org.json.JSONArray
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A YouTube video in search results, playlists and channels. */
data class YtItem(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String?,
)

/** What a playable YouTube track resolves to. */
data class Resolved(
    val pageUrl: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val streamUrl: String,
    val kbps: Int?,
    val khz: Int?,
    val expiresAtMs: Long,
)

data class Bookmark(val name: String, val url: String, val isChannel: Boolean)

class TrackUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** NewPipe's network hook, using the platform HTTP stack. */
private class UrlConnectionDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.requestMethod = request.httpMethod()
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", YouTube.BROWSER_UA)
        request.headers().forEach { (k, values) ->
            conn.setRequestProperty(k, values.joinToString(", "))
        }
        val body = request.dataToSend()
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code == 429) {
            conn.disconnect()
            throw ReCaptchaException("YouTube asked for a captcha", request.url())
        }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val headers = conn.headerFields.filterKeys { it != null }
        val latest = conn.url.toString()
        conn.disconnect()
        return Response(code, conn.responseMessage ?: "", headers, text, latest)
    }
}

object YouTube {
    const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                val loc = Locale.getDefault()
                val lang = loc.language.ifBlank { "en" }
                val country = loc.country.ifBlank { "US" }
                NewPipe.init(UrlConnectionDownloader(), Localization(lang, country), ContentCountry(country))
                ready = true
            }
        }
    }

    private val service get() = ServiceList.YouTube

    // ---- links

    private val videoRe = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\.be/|music\.youtube\.com/watch\?(?:.*&)?v=)([A-Za-z0-9_-]{11})""")

    fun videoId(url: String): String? = videoRe.find(url)?.groupValues?.get(1)

    fun isVideoLink(url: String) = videoId(url) != null
    fun isPlaylistLink(url: String) = url.contains("list=") && (url.contains("youtube.com") || url.contains("youtu.be"))
    fun isChannelLink(url: String) = Regex("""youtube\.com/(@|channel/|c/|user/)""").containsMatchIn(url)
    fun isYouTube(url: String) = url.contains("youtube.com") || url.contains("youtu.be")

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    // ---- search

    fun search(query: String): List<YtItem> {
        init()
        return guard {
            val handler = service.searchQHFactory.fromQuery(query.trim(), listOf("videos"), "")
            val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(service, handler)
            items(info.relatedItems)
        }
    }

    fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        init()
        return runCatching { service.suggestionExtractor.suggestionList(query.trim()) }.getOrDefault(emptyList())
    }

    private fun items(list: List<*>, uploaderFallback: String? = null): List<YtItem> =
        list.filterIsInstance<StreamInfoItem>().map {
            YtItem(
                url = it.url,
                title = it.name,
                uploader = it.uploaderName?.takeIf { u -> u.isNotBlank() } ?: uploaderFallback.orEmpty(),
                durationSec = it.duration.coerceAtLeast(0),
                thumbnail = it.thumbnails.firstOrNull()?.url,
            )
        }

    // ---- playlists and channels

    /** Loads every page of a playlist, calling [onPage] as pages arrive. Returns the playlist name. */
    fun importPlaylist(url: String, maxItems: Int = 500, onPage: (List<YtItem>) -> Unit): String {
        init()
        return guard {
            val info = PlaylistInfo.getInfo(service, url.trim())
            var count = 0
            val first = items(info.relatedItems)
            onPage(first); count += first.size
            var next: Page? = info.nextPage
            while (next != null && count < maxItems) {
                val page = PlaylistInfo.getMoreItems(service, url.trim(), next)
                val more = items(page.items)
                if (more.isEmpty()) break
                onPage(more); count += more.size
                next = if (page.hasNextPage()) page.nextPage else null
            }
            info.name
        }
    }

    /** Name and newest videos of a channel or playlist link. */
    fun bookmarkInfo(url: String): Pair<Bookmark, List<YtItem>> {
        init()
        val u = url.trim()
        return guard {
            when {
                isChannelLink(u) -> {
                    val info = ChannelInfo.getInfo(service, u)
                    val tab = info.tabs.firstOrNull { it.contentFilters.contains("videos") }
                    val videos = tab?.let { ChannelTabInfo.getInfo(service, it).relatedItems } ?: emptyList()
                    Bookmark(info.name, info.url, true) to items(videos, info.name)
                }
                isPlaylistLink(u) -> {
                    val info = PlaylistInfo.getInfo(service, u)
                    Bookmark(info.name, info.url, false) to items(info.relatedItems)
                }
                else -> throw TrackUnavailable("Not a YouTube channel or playlist link")
            }
        }
    }

    // ---- playback

    private val cache = ConcurrentHashMap<String, Resolved>()

    fun cached(id: String): Resolved? = cache[id]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000 }

    /** Finds the best progressive audio stream (M4A first, highest bitrate). Blocking. */
    fun resolve(url: String): Resolved {
        val id = videoId(url) ?: throw TrackUnavailable("Not a YouTube video link")
        cached(id)?.let { return it }
        init()
        val r = guard {
            val info = StreamInfo.getInfo(service, watchUrl(id))
            val stream = pick(info.audioStreams) ?: throw TrackUnavailable("No playable audio for this video")
            val expire = Uri.parse(stream.content).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
                ?: (System.currentTimeMillis() + 5 * 3600_000L)
            Resolved(
                pageUrl = info.url,
                title = info.name,
                uploader = info.uploaderName.orEmpty(),
                durationSec = info.duration,
                streamUrl = stream.content,
                kbps = stream.averageBitrate.takeIf { it > 0 },
                khz = when (stream.format) { MediaFormat.M4A -> 44; null -> null; else -> 48 },
                expiresAtMs = expire,
            )
        }
        cache[id] = r
        return r
    }

    private fun pick(streams: List<AudioStream>): AudioStream? {
        val direct = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        return direct.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: direct.maxByOrNull { it.averageBitrate }
    }

    /** User agent that matches the YouTube client the stream URL was made for. */
    fun userAgentFor(streamUrl: String): String {
        val client = Uri.parse(streamUrl).getQueryParameter("c").orEmpty().uppercase()
        return runCatching {
            when {
                client.startsWith("IOS") -> YoutubeParsingHelper.getIosUserAgent(null)
                client.startsWith("VISIONOS") -> YoutubeParsingHelper.getVisionOsUserAgent(null)
                client.startsWith("ANDROID") -> YoutubeParsingHelper.getAndroidUserAgent(null)
                else -> BROWSER_UA
            }
        }.getOrDefault(BROWSER_UA)
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: TrackUnavailable) {
        throw e
    } catch (e: GeographicRestrictionException) {
        throw TrackUnavailable("Not available in your country", e)
    } catch (e: AgeRestrictedContentException) {
        throw TrackUnavailable("Age-restricted video", e)
    } catch (e: ContentNotAvailableException) {
        throw TrackUnavailable("Video is unavailable", e)
    } catch (e: ReCaptchaException) {
        throw TrackUnavailable("YouTube is asking for a captcha. Try again later.", e)
    } catch (e: IOException) {
        throw TrackUnavailable("Network error", e)
    } catch (e: Exception) {
        throw TrackUnavailable("Could not load from YouTube (the app may need updating)", e)
    }

    // ---- SponsorBlock: skip the non-music parts of music videos

    data class Segment(val startSec: Double, val endSec: Double)

    private val segments = ConcurrentHashMap<String, List<Segment>>()

    fun segmentsCached(id: String): List<Segment>? = segments[id]

    /** Uses the privacy-preserving hash-prefix API: only 4 hex characters of the video id hash are sent. */
    fun loadSegments(id: String): List<Segment> {
        segments[id]?.let { return it }
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        val list = runCatching {
            val conn = URL("https://sponsor.ajay.app/api/skipSegments/${hash.take(4)}?categories=%5B%22music_offtopic%22%5D")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Klang (music player)")
            val code = conn.responseCode
            val text = if (code == 200) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
            conn.disconnect()
            if (text.isBlank()) return@runCatching emptyList<Segment>()
            val arr = JSONArray(text)
            val out = ArrayList<Segment>()
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                if (v.optString("videoID") != id) continue
                val segs = v.optJSONArray("segments") ?: continue
                for (k in 0 until segs.length()) {
                    val s = segs.getJSONObject(k)
                    if (s.optString("category") != "music_offtopic") continue
                    if (s.optString("actionType", "skip") != "skip") continue
                    val se = s.getJSONArray("segment")
                    out += Segment(se.getDouble(0), se.getDouble(1))
                }
            }
            out.sortedBy { it.startSec }
        }.getOrDefault(emptyList())
        segments[id] = list
        return list
    }
}
