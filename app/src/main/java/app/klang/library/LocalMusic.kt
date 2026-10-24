package app.klang.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    /** Average bitrate from file size, used when the decoder does not report one. */
    val estimatedKbps: Int?
        get() = if (durationMs > 0 && sizeBytes > 0) ((sizeBytes * 8L) / durationMs).toInt() else null
}

object LocalMusic {
    fun load(context: Context): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val songs = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val artist = c.getString(artistCol).orEmpty()
                    songs += Song(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        title = c.getString(titleCol).orEmpty().ifBlank { "Untitled" },
                        artist = if (artist == "<unknown>") "" else artist,
                        album = c.getString(albumCol).orEmpty(),
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                    )
                }
            }
        }
        return songs
    }
}
