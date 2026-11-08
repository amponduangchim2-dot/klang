package app.klang.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.res.ResourcesCompat
import app.klang.R
import app.klang.library.Song
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.min

/**
 * Cover art for Android Auto, the media notification and the lock screen, in the look of the
 * phone: every song is the player face (dot speaker, black OLED display with the title in
 * Klang Sans, dark red tuning strip, four round OP-1 keys); every folder is a tool tile.
 * The red dots in the speaker and the place of the tuning dot differ from song to song.
 */
object Covers {
    const val AUTHORITY = "app.klang.player.art"
    private const val SIZE = 600
    private const val VERSION = "v2"

    private const val RED = 0xFFF01E14.toInt()
    private const val DEEP = 0xFF9E130D.toInt()
    private const val TILE = 0xFFF5F4F1.toInt()
    private const val INK2 = 0xFF55534F.toInt()
    private const val DIM = 0xFF8E8E8E.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val KEY_LO = 0xFFF1F0ED.toInt()
    private const val KEY_LIP = 0xFFD0CEC7.toInt()

    fun songUri(s: Song): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
        .appendPath("song").appendPath(key(s.uri))
        .appendQueryParameter("t", s.title.take(200))
        .appendQueryParameter("a", s.artist.take(120))
        .appendQueryParameter("d", (s.durationMs / 1000).toString())
        .build()

    /** [kind] picks the icon; [name] is printed on the tile (a saved list shows its own name). */
    fun folderUri(kind: String, name: String? = null): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
        .appendPath("folder").appendPath(kind)
        .apply { if (name != null) appendQueryParameter("n", name.take(80)) }
        .build()

    private fun key(s: String): String = Integer.toHexString(s.hashCode())

    // ------------------------------------------------------------------ fonts and helpers

    private class Fonts(context: Context) {
        val medium: Typeface = ResourcesCompat.getFont(context, R.font.klang_medium) ?: Typeface.DEFAULT
        val semibold: Typeface = ResourcesCompat.getFont(context, R.font.klang_semibold) ?: Typeface.DEFAULT_BOLD
    }

    private fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, p: Paint) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p)
    }

    private fun circ(c: Canvas, x: Float, y: Float, r: Float, color: Int, p: Paint) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        c.drawCircle(x, y, r, p)
    }

    private fun ellipsize(p: Paint, t: String, max: Float): String {
        if (p.measureText(t) <= max) return t
        var s = t
        while (s.length > 1 && p.measureText("$s…") > max) s = s.dropLast(1)
        return "$s…"
    }

    /** At most [lines] lines; breaks at a space when there is one, otherwise between characters (Thai). */
    private fun wrap(p: Paint, text: String, max: Float, lines: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && out.size < lines) {
            if (out.size == lines - 1 || p.measureText(rest) <= max) { out += ellipsize(p, rest, max); break }
            var cut = p.breakText(rest, true, max, null).coerceAtLeast(1)
            val sp = rest.lastIndexOf(' ', cut)
            if (sp > cut / 2) cut = sp
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        return out
    }

    private fun fmt(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

    /** Round OP-1 key: shallow well, soft shadow to the bottom right, lip, face lit from the top left. */
    private fun opKey(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx - r * 1.16f, cy - r * 1.16f, cx + r * 1.16f, cy + r * 1.16f, 0x17000000, 0xE6FFFFFF.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.16f, p)
        p.shader = null
        for (i in 3 downTo 1) {
            val o = r * 0.07f + i * r * 0.025f
            p.color = ((0.075f * i / 3f * 255).toInt() shl 24)
            c.drawCircle(cx + o * 0.85f, cy + o, r + i * r * 0.035f, p)
        }
        circ(c, cx + r * 0.03f, cy + r * 0.04f, r, KEY_LIP, p)
        p.shader = RadialGradient(cx - r * 0.4f, cy - r * 0.4f, r * 1.6f, WHITE, KEY_LO, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.03f; p.color = 0xB3FFFFFF.toInt()
        c.drawCircle(cx, cy, r * 0.98f, p)
        p.style = Paint.Style.FILL
    }

    private fun glyph(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, p: Paint) {
        p.shader = null; p.style = Paint.Style.FILL
        p.color = if (kind == 1) RED else BLACK
        val path = Path()
        when (kind) {
            0 -> {   // previous
                c.drawRect(cx - s * 0.42f, cy - s * 0.4f, cx - s * 0.29f, cy + s * 0.4f, p)
                path.moveTo(cx + s * 0.4f, cy - s * 0.4f); path.lineTo(cx - s * 0.22f, cy); path.lineTo(cx + s * 0.4f, cy + s * 0.4f)
            }
            1 -> { path.moveTo(cx - s * 0.32f, cy - s * 0.42f); path.lineTo(cx + s * 0.42f, cy); path.lineTo(cx - s * 0.32f, cy + s * 0.42f) }
            2 -> { c.drawRoundRect(RectF(cx - s * 0.32f, cy - s * 0.32f, cx + s * 0.32f, cy + s * 0.32f), s * 0.06f, s * 0.06f, p); return }
            else -> {   // next
                c.drawRect(cx + s * 0.29f, cy - s * 0.4f, cx + s * 0.42f, cy + s * 0.4f, p)
                path.moveTo(cx - s * 0.4f, cy - s * 0.4f); path.lineTo(cx + s * 0.22f, cy); path.lineTo(cx - s * 0.4f, cy + s * 0.4f)
            }
        }
        path.close()
        c.drawPath(path, p)
    }

    // ------------------------------------------------------------------ song: the player face

    fun renderSong(context: Context, key: String, title: String, artist: String, durSec: Long): Bitmap {
        val S = SIZE.toFloat()
        val h = (key.toLongOrNull(16) ?: key.hashCode().toLong()).toInt()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val f = Fonts(context)
        c.drawColor(BLACK)

        val seam = S * 0.012f
        val rad = S * 0.035f
        val x0 = seam
        val y0 = seam
        val w = S - 2 * seam
        val hh = S - 2 * seam
        val col = (w - 3 * seam) / 4f
        val h1 = (hh - 2 * seam) * 0.56f
        val h2 = (hh - 2 * seam) * 0.15f
        val h3 = (hh - 2 * seam) - h1 - h2
        val yA = y0
        val yB = yA + h1 + seam
        val yC = yB + h2 + seam

        // speaker: dot grille, red dots are a still of the visualizer
        rr(c, x0, yA, col, h1, rad, TILE, p)
        val cols = 5
        val rows = 9
        val gx = col * 0.17f
        val pitch = (col - 2 * gx) / (cols - 1)
        val gy = yA + (h1 - pitch * (rows - 1)) / 2f
        for (i in 0 until cols) {
            val lvl = 2 + (((h ushr (i * 3)) and 0x7fffffff) % 6)
            for (j in 0 until rows) {
                val lit = rows - 1 - j < lvl
                circ(c, x0 + gx + i * pitch, gy + j * pitch, pitch * 0.26f, if (lit) RED else BLACK, p)
            }
        }

        // display: black OLED inside a tile
        val dx = x0 + col + seam
        val dw = col * 3 + seam * 2
        rr(c, dx, yA, dw, h1, rad, TILE, p)
        val pad = S * 0.022f
        val ox = dx + pad
        val oy = yA + pad
        val ow = dw - 2 * pad
        val oh = h1 - 2 * pad
        rr(c, ox, oy, ow, oh, rad * 0.8f, BLACK, p)
        val ip = S * 0.03f
        val tx = ox + ip
        val tw = ow - 2 * ip
        val line1 = oy + ip + S * 0.035f
        // ▶ play  ·  length
        p.color = WHITE
        val tri = Path().apply {
            val ts = S * 0.03f
            moveTo(tx, line1 - ts); lineTo(tx + ts * 0.86f, line1 - ts / 2f); lineTo(tx, line1); close()
        }
        c.drawPath(tri, p)
        p.typeface = f.medium; p.textSize = S * 0.042f; p.textAlign = Paint.Align.LEFT
        c.drawText("play", tx + S * 0.042f, line1, p)
        if (durSec > 0) {
            p.fontFeatureSettings = "tnum"; p.textAlign = Paint.Align.RIGHT
            c.drawText(fmt(durSec), tx + tw, line1, p)
            p.textAlign = Paint.Align.LEFT; p.fontFeatureSettings = null
        }
        p.textSize = S * 0.034f; p.color = DIM
        c.drawText("klang audio", tx, oy + ip + S * 0.08f, p)

        // title (up to 2 lines, shrinks to fit) and artist at the bottom of the display
        p.typeface = f.semibold
        var size = S * 0.072f
        var lines: List<String>
        while (true) {
            p.textSize = size
            lines = wrap(p, title.ifBlank { "untitled" }, tw, 2)
            if (lines.size == 1 || !lines[1].endsWith("…") || size <= S * 0.05f) break
            size -= S * 0.004f
        }
        val ab = oy + oh - ip
        val tb = if (artist.isNotBlank()) ab - S * 0.072f else ab
        val lh = size * 1.18f
        p.color = WHITE
        lines.forEachIndexed { i, ln -> c.drawText(ln, tx, tb - (lines.size - 1 - i) * lh, p) }
        if (artist.isNotBlank()) {
            p.typeface = f.medium; p.textSize = S * 0.045f; p.color = DIM
            c.drawText(ellipsize(p, artist, tw), tx, ab, p)
        }

        // tuning strip
        rr(c, x0, yB, w, h2, rad, TILE, p)
        val lx = x0 + S * 0.05f
        val lw = w - S * 0.1f
        val ly = yB + h2 * 0.42f
        rr(c, lx, ly - S * 0.0045f, lw, S * 0.009f, S * 0.005f, DEEP, p)
        val mins = ceil((if (durSec > 0) durSec else 240L) / 60.0).toInt().coerceAtLeast(1)
        val steps = mins * 4
        p.typeface = f.semibold; p.textSize = S * 0.03f; p.textAlign = Paint.Align.CENTER; p.fontFeatureSettings = "tnum"
        for (k in 0..steps) {
            val x = lx + lw * k / steps
            val major = k % 4 == 0
            val len = if (major) S * 0.024f else S * 0.011f
            p.color = DEEP
            c.drawRect(x - S * 0.0018f, ly - S * 0.008f - len, x - S * 0.0018f + (if (major) S * 0.0036f else S * 0.0024f), ly - S * 0.008f, p)
            if (major && mins <= 9) c.drawText((k / 4).toString(), x, ly + S * 0.045f, p)
        }
        p.textAlign = Paint.Align.LEFT; p.fontFeatureSettings = null
        val nx = lx + lw * (0.18f + ((h ushr 9) % 60) / 100f)
        circ(c, nx + S * 0.003f, ly + S * 0.004f, S * 0.016f, 0x2E000000, p)
        circ(c, nx, ly, S * 0.016f, RED, p)

        // four round keys: prev, play (red), stop, next
        for (i in 0 until 4) {
            val kx = x0 + i * (col + seam)
            rr(c, kx, yC, col, h3, rad, TILE, p)
            val r = min(col, h3) * 0.33f
            val cx = kx + col / 2f
            val cy = yC + h3 / 2f
            opKey(c, cx, cy, r, p)
            glyph(c, i, cx, cy, r * 0.62f, p)
        }
        return bmp
    }

    // ------------------------------------------------------------------ folder: a tool tile

    private fun label(kind: String): Pair<String, String> = when (kind) {
        "queue" -> "playlist" to "now playing"
        "favorites" -> "favorites" to "loved songs"
        "most" -> "most played" to "top 100"
        "history" -> "recently played" to "history"
        "lists" -> "saved lists" to "library"
        "list" -> "saved list" to "saved list"
        "local" -> "all songs" to "on this phone"
        else -> kind to "klang audio"
    }

    fun renderFolder(context: Context, kind: String, nameOverride: String?): Bitmap {
        val S = SIZE.toFloat()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val f = Fonts(context)
        c.drawColor(BLACK)
        val seam = S * 0.012f
        rr(c, seam, seam, S - 2 * seam, S - 2 * seam, S * 0.05f, TILE, p)
        val pad = S * 0.1f

        // outline icon in a circle
        val ir = S * 0.15f
        val icx = pad + ir
        val icy = pad + ir
        p.style = Paint.Style.STROKE; p.color = BLACK; p.strokeWidth = S * 0.016f
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        c.drawCircle(icx, icy, ir, p)
        p.strokeWidth = S * 0.018f
        val u = S * 0.0095f
        val x = icx
        val y = icy
        when (kind) {
            "queue" -> for (i in 0 until 3) { val yy = y - 6 * u + i * 6 * u; c.drawLine(x - 7 * u, yy, x + 7 * u, yy, p) }
            "favorites" -> {
                val path = Path().apply {
                    moveTo(x, y + 7 * u)
                    cubicTo(x - 13 * u, y - 1 * u, x - 7 * u, y - 10 * u, x, y - 4 * u)
                    cubicTo(x + 7 * u, y - 10 * u, x + 13 * u, y - 1 * u, x, y + 7 * u)
                    close()
                }
                p.style = Paint.Style.FILL; p.color = RED; c.drawPath(path, p)
            }
            "most" -> listOf(-6f to 6f, 0f to 11f, 6f to 3f).forEach { (dxx, hgt) -> c.drawLine(x + dxx * u, y + 6 * u, x + dxx * u, y + 6 * u - hgt * u, p) }
            "history" -> {
                c.drawCircle(x, y, 8 * u, p)
                c.drawLine(x, y, x, y - 5 * u, p)
                c.drawLine(x, y, x + 4 * u, y + 2 * u, p)
            }
            "lists", "list" -> {
                c.drawRoundRect(RectF(x - 7 * u, y - 7 * u, x + 7 * u, y + 7 * u), 2 * u, 2 * u, p)
                c.drawLine(x - 3.5f * u, y - 2 * u, x + 3.5f * u, y - 2 * u, p)
                c.drawLine(x - 3.5f * u, y + 2.5f * u, x + 3.5f * u, y + 2.5f * u, p)
            }
            else -> {   // all songs: a note
                val path = Path().apply { moveTo(x - 2 * u, y + 5 * u); lineTo(x - 2 * u, y - 7 * u); lineTo(x + 6 * u, y - 9 * u); lineTo(x + 6 * u, y + 3 * u) }
                c.drawPath(path, p)
                circ(c, x - 4.5f * u, y + 5 * u, 2.6f * u, BLACK, p)
                circ(c, x + 3.5f * u, y + 3 * u, 2.6f * u, BLACK, p)
            }
        }
        p.style = Paint.Style.FILL

        // status light
        circ(c, S - pad - S * 0.02f, pad + S * 0.03f, S * 0.04f, 0x33F01E14, p)
        circ(c, S - pad - S * 0.02f, pad + S * 0.03f, S * 0.026f, RED, p)

        // name and sub line
        val (name0, sub) = label(kind)
        val name = nameOverride?.takeIf { it.isNotBlank() } ?: name0
        val maxW = S - 2 * pad
        p.typeface = f.semibold; p.color = BLACK
        var fs = S * 0.13f
        p.textSize = fs
        while (p.measureText(name) > maxW && fs > S * 0.08f) { fs -= S * 0.005f; p.textSize = fs }
        c.drawText(ellipsize(p, name, maxW), pad, S - pad - S * 0.1f, p)
        p.typeface = f.medium; p.textSize = S * 0.078f; p.color = INK2
        c.drawText(ellipsize(p, sub, maxW), pad, S - pad, p)
        return bmp
    }

    /** Draws the cover once and keeps it in the cache. */
    @Synchronized
    fun file(context: Context, uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size < 2) return null
        val dir = File(context.cacheDir, "art").apply { mkdirs() }
        val query = uri.encodedQuery.orEmpty()
        val tag = Integer.toHexString(query.hashCode())
        val f = File(dir, "${seg[0]}-${seg[1].replace(Regex("[^A-Za-z0-9_-]"), "_")}-$tag-$VERSION.png")
        if (f.exists() && f.length() > 0) return f
        val bmp = when (seg[0]) {
            "song" -> renderSong(
                context, seg[1],
                uri.getQueryParameter("t").orEmpty(),
                uri.getQueryParameter("a").orEmpty(),
                uri.getQueryParameter("d")?.toLongOrNull() ?: 0L,
            )
            "folder" -> renderFolder(context, seg[1], uri.getQueryParameter("n"))
            else -> return null
        }
        // keep the cache small: drop the oldest covers past 400 files
        dir.listFiles()?.takeIf { it.size > 400 }?.sortedBy { it.lastModified() }?.take(100)?.forEach { it.delete() }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }
}

/** Serves [Covers] to Android Auto and the system media controls. Read only, images only. */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
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
