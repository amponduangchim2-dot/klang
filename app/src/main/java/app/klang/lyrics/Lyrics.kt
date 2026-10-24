package app.klang.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val synced: List<LyricLine>,
    val plain: String?,
    val trackName: String,
    val artistName: String,
)

class LyricsUnavailable(message: String) : Exception(message)

/** Lyrics from LRCLIB (lrclib.net), the free synced-lyrics database Droidamp also uses. */
object LrcLib {
    private const val UA = "Klang (music player)"

    private val noise = Regex(
        """\s*[(\[](?:[^)\]]*?(?:official|video|audio|lyrics?|lyric video|visuali[sz]er|mv|m/v|hd|hq|4k|remaster(?:ed)?|live|explicit|clean|color coded|เนื้อเพลง|ซับไทย)[^)\]]*?)[)\]]""",
        RegexOption.IGNORE_CASE
    )

    /** Splits "Artist - Title (Official Video)" style names into a clean artist and title. */
    fun clean(title: String, artist: String): Pair<String, String> {
        var t = title.replace(noise, "")
            .replace(Regex("""(?i)\s*[(\[]\s*(ft\.?|feat\.?|featuring)\s[^)\]]*[)\]]"""), "")
            .replace(Regex("""(?i)\s*\|\s*(lyrics?|official.*|audio|mv|video|เนื้อเพลง).*$"""), "")
            .replace(Regex("\\s+"), " ").trim()
        var a = artist.removeSuffix(" - Topic").replace(Regex("(?i)\\s*VEVO$"), "").trim()
        val parts = t.split(Regex("\\s[-–—|]\\s"), limit = 2)
        if (parts.size == 2) {
            // "Artist - Title" is the common upload format
            a = parts[0].trim()
            t = parts[1].trim()
        }
        t = t.replace(Regex("""(?i)\s*(ft\.|feat\.?|featuring)\s.*$"""), "").trim()
        return a to t
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        val code = conn.responseCode
        val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to body
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fromJson(o: JSONObject): LyricsResult? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { parseLrc(it) }.orEmpty()
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced.isEmpty() && plain == null) return null
        return LyricsResult(synced, plain, o.optString("trackName"), o.optString("artistName"))
    }

    /** Blocking. Throws [LyricsUnavailable] with a message to show. */
    fun find(rawTitle: String, rawArtist: String, durationMs: Long): LyricsResult {
        val (artist, title) = clean(rawTitle, rawArtist)
        if (title.isBlank()) throw LyricsUnavailable("No lyrics found for this song.")
        try {
            // 1. exact lookup by artist, title and length
            if (artist.isNotBlank()) {
                val dur = if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
                val (code, body) = get("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}$dur")
                if (code == 200) fromJson(JSONObject(body))?.let { return it }
            }
            // 2. search, then pick the closest length, preferring synced lyrics
            val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            val (code, body) = get("https://lrclib.net/api/search?q=${enc(q)}")
            if (code != 200) throw LyricsUnavailable("Could not reach the lyrics service.")
            val arr = JSONArray(body)
            val candidates = (0 until arr.length()).map { arr.getJSONObject(it) }
            val best = candidates.sortedWith(
                compareBy<JSONObject> { if (it.optString("syncedLyrics").isNullOrBlank() || it.optString("syncedLyrics") == "null") 1 else 0 }
                    .thenBy { if (durationMs > 0) abs(it.optDouble("duration", 0.0) * 1000 - durationMs) else 0.0 }
            ).firstOrNull { durationMs <= 0 || abs(it.optDouble("duration", 0.0) * 1000 - durationMs) < 20_000 }
                ?: candidates.firstOrNull()
            return best?.let { fromJson(it) } ?: throw LyricsUnavailable("No lyrics found for this song.")
        } catch (e: LyricsUnavailable) {
            throw e
        } catch (e: java.io.IOException) {
            throw LyricsUnavailable("Could not reach the lyrics service.")
        } catch (e: Exception) {
            throw LyricsUnavailable("No lyrics found for this song.")
        }
    }

    private val tag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            val tags = tag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val words = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LyricLine(min * 60_000 + sec * 1000 + ms, words)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
