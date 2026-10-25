package app.klang.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.klang.library.Song
import org.json.JSONArray
import org.json.JSONObject

/** Conversions between Klang songs and player items, and the saved "what was playing" state. */
object Items {

    fun fromSong(s: Song): MediaItem = MediaItem.Builder()
        .setMediaId(s.uri)
        .setUri(s.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putLong("id", s.id)
                    putLong("d", s.durationMs)
                    putLong("z", s.sizeBytes)
                })
                .build()
        )
        .build()

    fun toSong(item: MediaItem, index: Int): Song {
        val md = item.mediaMetadata
        val x = md.extras
        val uri = item.mediaId.ifBlank { item.localConfiguration?.uri?.toString().orEmpty() }
        return Song(
            x?.getLong("id", -1L - index) ?: (-1L - index),
            uri,
            md.title?.toString() ?: uri.substringAfterLast('/'),
            md.artist?.toString().orEmpty(),
            md.albumTitle?.toString().orEmpty(),
            x?.getLong("d") ?: 0L,
            x?.getLong("z") ?: 0L,
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences("klang", Context.MODE_PRIVATE)

    fun saveQueue(context: Context, songs: List<Song>, index: Int, positionMs: Long) {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("uri", s.uri).put("t", s.title).put("a", s.artist)
                .put("al", s.album).put("d", s.durationMs).put("z", s.sizeBytes))
        }
        prefs(context).edit().putString("queue", arr.toString())
            .putInt("queueIndex", index)
            .putLong("queuePos", positionMs.coerceAtLeast(0L))
            .apply()
    }

    data class Saved(val songs: List<Song>, val index: Int, val positionMs: Long)

    fun loadQueue(context: Context): Saved? {
        val p = prefs(context)
        val arr = runCatching { JSONArray(p.getString("queue", "[]")) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        val songs = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Song(o.optLong("id"), o.getString("uri"), o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"), o.optLong("z"))
        }
        return Saved(songs, p.getInt("queueIndex", 0).coerceIn(0, songs.size - 1), p.getLong("queuePos", 0L))
    }
}
