package app.klang.cast

import app.klang.youtube.YouTube

/** Turns a YouTube clip into something a TV can play, through the phone's relay. */
object YouTubeCast {
    /** Call off the main thread. Null when there is nothing a TV could play. */
    fun media(pageUrl: String, startMs: Long, maxHeight: Int = 1080): CastMedia? {
        val st = YouTube.castStreams(pageUrl, maxHeight)
        fun headers(u: String) = StreamProxy.Headers(null, YouTube.userAgentFor(u), null)
        val title = st.title.ifBlank { "YouTube" }
        if (st.live) {
            val hls = st.hlsUrl ?: return null
            return CastMedia(hls, title, "application/x-mpegURL", headers(hls))
        }
        val single = st.muxedUrl?.let { CastMedia(it, title, "video/mp4", headers(it), startMs = startMs) }
        val v = st.video
        val a = st.audio
        if (v == null || a == null) return single
        return CastMedia(
            url = v.url, title = title, mime = "application/dash+xml", headers = headers(v.url),
            sources = listOf(v.url, a.url),
            manifest = { relayed -> mpd(st.durationSec, v, relayed[0], a, relayed[1]) },
            fallback = single,
            startMs = startMs,
        )
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mpd(durationSec: Long, v: YouTube.DashRep, vUrl: String, a: YouTube.DashRep, aUrl: String): String {
        fun rep(id: String, r: YouTube.DashRep, url: String, video: Boolean): String {
            val size = if (video) """ width="${r.width}" height="${r.height}"""" + (if (r.fps > 0) """ frameRate="${r.fps}"""" else "") else ""
            val codecs = if (r.codecs.isNotBlank()) """ codecs="${esc(r.codecs)}"""" else ""
            val ch = if (video) "" else """<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>"""
            return """<AdaptationSet contentType="${if (video) "video" else "audio"}" mimeType="${esc(r.mime)}" subsegmentAlignment="true" startWithSAP="1">""" +
                """<Representation id="$id" bandwidth="${r.bitrate.coerceAtLeast(1)}"$codecs$size>$ch""" +
                """<BaseURL>${esc(url)}</BaseURL>""" +
                """<SegmentBase indexRange="${r.indexStart}-${r.indexEnd}"><Initialization range="${r.initStart}-${r.initEnd}"/></SegmentBase>""" +
                """</Representation></AdaptationSet>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT2S" mediaPresentationDuration="PT${durationSec.coerceAtLeast(1)}S">""" +
            """<Period start="PT0S">""" + rep("v", v, vUrl, true) + rep("a", a, aUrl, false) + "</Period></MPD>"
    }
}
