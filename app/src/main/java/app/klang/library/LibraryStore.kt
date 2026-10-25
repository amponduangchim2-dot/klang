package app.klang.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Klang remembers about songs: play counts, when they were last played, favorites.
 * Song details are kept too, so favorites and history also work for YouTube tracks.
 * Stored as one small JSON file in the app's private storage.
 */
class LibraryStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: LibraryStore? = null
        /** One store per process, shared by the screen and the player service (car, widget). */
        fun get(context: Context): LibraryStore =
            instance ?: synchronized(this) { instance ?: LibraryStore(context.applicationContext).also { instance = it } }
    }

    private val file = File(context.filesDir, "library.json")

    private class Meta(var song: Song, var plays: Int = 0, var last: Long = 0L, var fav: Boolean = false, var favAt: Long = 0L)

    private val byUri = LinkedHashMap<String, Meta>()
    private val history = ArrayList<Pair<String, Long>>()   // newest first

    init { load() }

    private fun Song.toJson() = JSONObject().put("id", id).put("uri", uri).put("t", title).put("a", artist)
        .put("al", album).put("d", durationMs).put("z", sizeBytes)

    private fun songOf(o: JSONObject) = Song(o.optLong("id"), o.getString("uri"), o.optString("t"), o.optString("a"),
        o.optString("al"), o.optLong("d"), o.optLong("z"))

    @Synchronized private fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(text)
            val songs = root.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songs.length()) {
                val o = songs.getJSONObject(i)
                val s = songOf(o.getJSONObject("s"))
                byUri[s.uri] = Meta(s, o.optInt("p"), o.optLong("l"), o.optBoolean("f"), o.optLong("fa"))
            }
            val h = root.optJSONArray("history") ?: JSONArray()
            for (i in 0 until h.length()) {
                val o = h.getJSONObject(i)
                history += o.getString("u") to o.optLong("t")
            }
        }
    }

    @Synchronized private fun save() {
        val songs = JSONArray()
        byUri.values.forEach { m ->
            songs.put(JSONObject().put("s", m.song.toJson()).put("p", m.plays).put("l", m.last).put("f", m.fav).put("fa", m.favAt))
        }
        val h = JSONArray()
        history.take(200).forEach { (u, t) -> h.put(JSONObject().put("u", u).put("t", t)) }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("songs", songs).put("history", h).toString())
            tmp.renameTo(file)
        }
    }

    private fun meta(song: Song) = byUri.getOrPut(song.uri) { Meta(song) }.also {
        // keep the best known details
        if (song.title.isNotBlank()) it.song = song.copy(durationMs = maxOf(song.durationMs, it.song.durationMs))
    }

    @Synchronized fun recordPlay(song: Song) {
        val now = System.currentTimeMillis()
        val m = meta(song)
        m.plays++
        m.last = now
        history.removeAll { it.first == song.uri }
        history.add(0, song.uri to now)
        while (history.size > 200) history.removeAt(history.size - 1)
        save()
    }

    @Synchronized fun setFavorite(song: Song, fav: Boolean) {
        val m = meta(song)
        m.fav = fav
        if (fav) m.favAt = System.currentTimeMillis()
        save()
    }

    @Synchronized fun favoriteUris(): Set<String> = byUri.values.filter { it.fav }.map { it.song.uri }.toSet()

    @Synchronized fun favorites(): List<Song> = byUri.values.filter { it.fav }.sortedByDescending { it.favAt }.map { it.song }

    @Synchronized fun mostPlayed(limit: Int = 100): List<Pair<Song, Int>> =
        byUri.values.filter { it.plays > 0 }.sortedWith(compareByDescending<Meta> { it.plays }.thenByDescending { it.last })
            .take(limit).map { it.song to it.plays }

    @Synchronized fun history(limit: Int = 100): List<Pair<Song, Long>> =
        history.take(limit).mapNotNull { (u, t) -> byUri[u]?.let { it.song to t } }

    @Synchronized fun clearHistory() {
        history.clear()
        byUri.values.forEach { it.plays = 0; it.last = 0 }
        save()
    }

    /** Merges older favorites kept as plain URIs (before this store existed). */
    @Synchronized fun importFavoriteUris(uris: Set<String>, lookup: (String) -> Song?) {
        var changed = false
        for (u in uris) {
            val m = byUri[u]
            if (m == null) {
                val s = lookup(u) ?: continue
                byUri[u] = Meta(s, fav = true, favAt = System.currentTimeMillis())
                changed = true
            } else if (!m.fav) { m.fav = true; changed = true }
        }
        if (changed) save()
    }
}
