package app.klang.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.klang.library.Song
import app.klang.youtube.YouTube
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Cover art for Android Auto, the media notification and the lock screen.
 *
 * Songs on the phone: the OP-1 screen. A black display set in the aluminium body, a white
 * line trace and a red level bar; the trace and the level differ from song to song. No title
 * and no keys on the picture: the car shows those beside it.
 * YouTube tracks: the clip's own picture, cropped to a square.
 * Folders: the same screen with a white line sign.
 */
object Covers {
    const val AUTHORITY = "app.klang.player.art"
    private const val SIZE = 600
    private const val VERSION = "v3"

    private const val BODY = 0xFFECECE9.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val RED = 0xFFF01E14.toInt()
    private const val TRACK = 0xFF262626.toInt()

    private val idRe = Regex("^[A-Za-z0-9_-]{11}$")

    fun songUri(s: Song): Uri {
        val id = if (YouTube.isVideoLink(s.uri)) YouTube.videoId(s.uri) else null
        val b = Uri.Builder().scheme("content").authority(AUTHORITY)
        return if (id != null) b.appendPath("yt").appendPath(id).build()
        else b.appendPath("song").appendPath(key(s.uri)).build()
    }

    /** [kind] picks the sign on the screen. */
    fun folderUri(kind: String, @Suppress("UNUSED_PARAMETER") name: String? = null): Uri =
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("folder").appendPath(kind).build()

    private fun key(s: String): String = Integer.toHexString(s.hashCode())

    // ------------------------------------------------------------------ the screen

    private class Screen(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun frame(c: Canvas, p: Paint): Screen {
        val s = SIZE.toFloat()
        c.drawColor(BODY)
        val m = s * 0.07f
        p.style = Paint.Style.FILL; p.color = BLACK
        c.drawRoundRect(RectF(m, m, s - m, s - m), s * 0.05f, s * 0.05f, p)
        return Screen(m, m, s - 2 * m, s - 2 * m)
    }

    private fun levelBar(c: Canvas, sc: Screen, level: Float, p: Paint) {
        val x = sc.x + sc.w * 0.85f
        val y = sc.y + sc.h * 0.18f
        val w = sc.w * 0.055f
        val h = sc.h * 0.64f
        p.style = Paint.Style.FILL
        p.color = TRACK; c.drawRoundRect(RectF(x, y, x + w, y + h), w / 2, w / 2, p)
        p.color = RED; c.drawRoundRect(RectF(x, y + h * (1 - level), x + w, y + h), w / 2, w / 2, p)
    }

    /** Small xorshift generator, so a song always gets the same picture. */
    private class Rng(seed: Int) {
        private var s = if (seed == 0) 1 else seed
        fun next(): Float {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            return ((s.toLong() and 0xffffffffL).toDouble() / 4294967296.0).toFloat()
        }
    }

    fun renderSong(key: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sc = frame(c, p)
        val r = Rng((key.toLongOrNull(16) ?: key.hashCode().toLong()).toInt())
        val a = 1 + (r.next() * 3).toInt()
        val b = a + 1 + (r.next() * 2).toInt()
        val ph = (PI / 2 * (r.next() * 2).toInt() + PI / 4)
        val cx = sc.x + sc.w * 0.42f
        val cy = sc.y + sc.h * 0.5f
        val ax = sc.w * 0.3f
        val ay = sc.h * 0.32f
        val path = Path()
        for (t in 0..720) {
            val u = t / 720.0 * PI * 2
            val x = cx + (sin(a * u + ph) * ax).toFloat()
            val y = cy + (sin(b * u) * ay).toFloat()
            if (t == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.style = Paint.Style.STROKE; p.color = WHITE; p.strokeWidth = SIZE * 0.011f; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        levelBar(c, sc, 0.3f + r.next() * 0.6f, p)
        return bmp
    }

    fun renderFolder(kind: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sc = frame(c, p)
        val x = sc.x + sc.w * 0.42f
        val y = sc.y + sc.h * 0.5f
        val k = SIZE * 0.026f
        p.style = Paint.Style.STROKE; p.color = WHITE; p.strokeWidth = SIZE * 0.026f
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        when (kind) {
            "queue" -> for (i in 0 until 3) { val yy = y - 6 * k + i * 6 * k; c.drawLine(x - 7 * k, yy, x + 7 * k, yy, p) }
            "favorites" -> {
                val h = Path().apply {
                    moveTo(x, y + 7 * k)
                    cubicTo(x - 13 * k, y - k, x - 7 * k, y - 10 * k, x, y - 4 * k)
                    cubicTo(x + 7 * k, y - 10 * k, x + 13 * k, y - k, x, y + 7 * k)
                    close()
                }
                p.style = Paint.Style.FILL; p.color = RED; c.drawPath(h, p)
            }
            "most" -> listOf(-6f to 6f, 0f to 11f, 6f to 3f).forEach { (dx, hh) -> c.drawLine(x + dx * k, y + 6 * k, x + dx * k, y + 6 * k - hh * k, p) }
            "history" -> { c.drawCircle(x, y, 8 * k, p); c.drawLine(x, y, x, y - 5 * k, p); c.drawLine(x, y, x + 4 * k, y + 2 * k, p) }
            "lists", "list" -> {
                c.drawRoundRect(RectF(x - 7 * k, y - 7 * k, x + 7 * k, y + 7 * k), 2 * k, 2 * k, p)
                c.drawLine(x - 3.5f * k, y - 2 * k, x + 3.5f * k, y - 2 * k, p)
                c.drawLine(x - 3.5f * k, y + 2.5f * k, x + 3.5f * k, y + 2.5f * k, p)
            }
            "youtube" -> {
                c.drawRoundRect(RectF(x - 10 * k, y - 7 * k, x + 10 * k, y + 7 * k), 4 * k, 4 * k, p)
                val t = Path().apply { moveTo(x - 2.5f * k, y - 3.5f * k); lineTo(x + 4 * k, y); lineTo(x - 2.5f * k, y + 3.5f * k); close() }
                p.style = Paint.Style.FILL; p.color = RED; c.drawPath(t, p)
            }
            "search" -> { c.drawCircle(x - 1.5f * k, y - 1.5f * k, 6 * k, p); c.drawLine(x + 3 * k, y + 3 * k, x + 8 * k, y + 8 * k, p) }
            else -> {   // all songs: a note
                c.drawPath(Path().apply { moveTo(x - 2 * k, y + 5 * k); lineTo(x - 2 * k, y - 7 * k); lineTo(x + 6 * k, y - 9 * k); lineTo(x + 6 * k, y + 3 * k) }, p)
                p.style = Paint.Style.FILL
                c.drawCircle(x - 4.5f * k, y + 5 * k, 2.6f * k, p)
                c.drawCircle(x + 3.5f * k, y + 3 * k, 2.6f * k, p)
            }
        }
        val level = mapOf("favorites" to 0.85f, "most" to 0.7f, "history" to 0.5f, "lists" to 0.35f, "list" to 0.35f, "queue" to 0.6f, "youtube" to 0.75f, "search" to 0.55f)[kind] ?: 0.45f
        levelBar(c, sc, level, p)
        return bmp
    }

    // ------------------------------------------------------------------ YouTube pictures

    private fun download(url: String): Bitmap? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", YouTube.BROWSER_UA)
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally { conn.disconnect() }
    }.getOrNull()

    /**
     * The clip's picture as a square. hq720 is a 16:9 picture; hqdefault is 4:3 with black bands
     * above and below a 16:9 picture, so the square is taken from inside the picture either way.
     */
    fun renderYouTube(id: String): Bitmap? {
        if (!idRe.matches(id)) return null
        val src = download("https://i.ytimg.com/vi/$id/hq720.jpg")
            ?: download("https://i.ytimg.com/vi/$id/hqdefault.jpg")
            ?: return null
        val pictureH = min(src.height.toFloat(), src.width * 9f / 16f)
        val side = pictureH.toInt()
        val left = (src.width - side) / 2
        val top = ((src.height - side) / 2f).toInt()
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(left, top, left + side, top + side), Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /** Draws the cover once and keeps it in the cache. */
    fun file(context: Context, uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size < 2) return null
        val dir = File(context.cacheDir, "art").apply { mkdirs() }
        val name = "${seg[0]}-${seg[1].replace(Regex("[^A-Za-z0-9_-]"), "_")}-$VERSION.png"
        val f = File(dir, name)
        synchronized(this) { if (f.exists() && f.length() > 0) return f }
        val bmp = when (seg[0]) {
            "song" -> renderSong(seg[1])
            "folder" -> renderFolder(seg[1])
            // no network or no picture: the screen cover instead
            "yt" -> renderYouTube(seg[1]) ?: renderSong(Integer.toHexString(seg[1].hashCode()))
            else -> return null
        }
        synchronized(this) {
            dir.listFiles()?.takeIf { it.size > 400 }?.sortedBy { it.lastModified() }?.take(100)?.forEach { it.delete() }
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { bmp.compress(if (seg[0] == "yt") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, it) }
            tmp.renameTo(f)
        }
        bmp.recycle()
        return f
    }
}

/** Serves [Covers] to Android Auto and the system media controls. Read only, images only. */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.pathSegments.firstOrNull() == "yt") "image/jpeg" else "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        val ctx = context ?: return null
        val f = Covers.file(ctx, uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
