package app.klang.playlist

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.klang.library.Song
import java.io.File

/** One row in the current playlist. [key] is unique even when the same song appears twice. */
data class QueueEntry(val key: Long, val song: Song)

private fun keyFor(uri: String) = -((uri.hashCode().toLong() and 0xffffffffL) + 1L)

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "webm", "mp4", "3gp", "amr", "aiff", "aif")

fun songFromUrl(url: String): Song {
    val u = url.trim()
    val last = Uri.parse(u).lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    return Song(keyFor(u), u, last?.let { Uri.decode(it) } ?: u, "", "", 0L, 0L)
}

/** Reads title / artist / album / duration of a picked file. */
fun songFromDocument(context: Context, uri: Uri): Song {
    val resolver = context.contentResolver
    var name: String? = null
    var size = 0L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    var title: String? = null
    var artist = ""
    var album = ""
    var dur = 0L
    runCatching {
        MediaMetadataRetriever().apply {
            setDataSource(context, uri)
            title = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            dur = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release()
        }
    }
    val fallback = name?.substringBeforeLast('.') ?: uri.lastPathSegment ?: "Untitled"
    return Song(keyFor(uri.toString()), uri.toString(), title?.takeIf { it.isNotBlank() } ?: fallback, artist, album, dur, size)
}

fun keepAccess(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** All audio files inside a folder picked with the system folder picker (recursive, up to [limit]). */
fun audioInTree(context: Context, tree: Uri, limit: Int = 3000): List<Uri> {
    val resolver: ContentResolver = context.contentResolver
    val out = ArrayList<Uri>()
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val stack = ArrayDeque<String>().apply { add(rootId) }
    val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    while (stack.isNotEmpty() && out.size < limit) {
        val parent = stack.removeLast()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        runCatching {
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> stack.add(id)
                        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT ->
                            out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
    }
    return out
}

/** True when the entry can still be opened. Web links are assumed playable. */
fun isPlayable(context: Context, song: Song): Boolean {
    val uri = Uri.parse(song.uri)
    return when (uri.scheme) {
        "content" -> runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        "file" -> File(uri.path ?: "").canRead()
        null, "" -> File(song.uri).canRead()
        else -> true
    }
}

/** Extended M3U, the format Winamp uses. */
object M3u {
    fun write(songs: List<Song>): String = buildString {
        append("#EXTM3U\n")
        for (s in songs) {
            val name = if (s.artist.isNotBlank()) "${s.artist} - ${s.title}" else s.title
            append("#EXTINF:${s.durationMs / 1000},$name\n")
            append(s.uri).append('\n')
        }
    }

    /**
     * Parses M3U text. Absolute paths and URLs are kept; relative paths are matched
     * against [library] by file name. Returns the songs and the number that could not be found.
     */
    fun parse(text: String, library: List<Song>): Pair<List<Song>, Int> {
        val out = ArrayList<Song>()
        var missing = 0
        var pendingTitle: String? = null
        var pendingDur = 0L
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val body = line.substringAfter(':')
                pendingDur = body.substringBefore(',').trim().toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L
                pendingTitle = body.substringAfter(',', "").trim().ifBlank { null }
                continue
            }
            if (line.startsWith("#")) continue
            val song: Song? = when {
                line.contains("://") -> {
                    val base = songFromUrl(line)
                    library.firstOrNull { it.uri == line } ?: base
                }
                line.startsWith("/") -> {
                    val f = File(line)
                    library.firstOrNull { it.uri == line } ?: Song(keyFor(line), Uri.fromFile(f).toString(), f.nameWithoutExtension, "", "", 0L, f.length())
                }
                else -> {
                    val file = line.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
                    library.firstOrNull { it.title.equals(file, true) }
                }
            }
            if (song == null) {
                missing++
            } else {
                var s = song
                val t = pendingTitle
                if (t != null && (s.title == s.uri || s.title.isBlank() || s.artist.isBlank() && s.id < 0)) {
                    val parts = t.split(" - ", limit = 2)
                    s = if (parts.size == 2) s.copy(artist = parts[0], title = parts[1]) else s.copy(title = t)
                }
                if (s.durationMs == 0L && pendingDur > 0) s = s.copy(durationMs = pendingDur)
                out += s
            }
            pendingTitle = null
            pendingDur = 0L
        }
        return out to missing
    }
}

/** Saved playlists, kept as .m3u files in the app's private storage. */
class SavedLists(context: Context) {
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }

    private fun safe(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

    fun names(): List<String> = dir.listFiles { f -> f.extension == "m3u" }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.nameWithoutExtension } ?: emptyList()

    fun exists(name: String) = File(dir, safe(name) + ".m3u").exists()

    fun save(name: String, songs: List<Song>) = File(dir, safe(name) + ".m3u").writeText(M3u.write(songs))

    fun read(name: String): String? = File(dir, safe(name) + ".m3u").takeIf { it.exists() }?.readText()

    fun delete(name: String) { File(dir, safe(name) + ".m3u").delete() }

    fun file(name: String): File = File(dir, safe(name) + ".m3u")
}
