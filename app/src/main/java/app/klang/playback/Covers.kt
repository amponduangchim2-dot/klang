package app.klang.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import app.klang.R
import app.klang.library.Song
import app.klang.share.ShareCard
import app.klang.ui.Skins
import java.io.File
import java.io.FileOutputStream

/**
 * Cover art in the Klang Audio look, for Android Auto, the media notification and the lock screen.
 * Every song gets the graphic record; the label colour and the angle of the white wedge come
 * from the song, so covers in a grid look related but not identical.
 */
object Covers {
    const val AUTHORITY = "app.klang.player.art"
    private const val SIZE = 600

    fun songUri(s: Song): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
        .appendPath("song").appendPath(key(s.uri)).build()

    fun folderUri(kind: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
        .appendPath("folder").appendPath(kind).build()

    private fun key(s: String): String = Integer.toHexString(s.hashCode())

    // label colours, after the coloured caps of the OP-1: red, orange, blue, sand, grey, white
    private val labels = intArrayOf(0xFFF01E14.toInt(), 0xFFFF7A1A.toInt(), 0xFF2F5BEA.toInt(), 0xFFD9C8A9.toInt(), 0xFF9A9893.toInt(), 0xFFFFFFFF.toInt())

    fun renderSong(context: Context, key: String): Bitmap {
        val h = key.toLongOrNull(16) ?: key.hashCode().toLong()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val skin = Skins.studio
        c.drawColor(0xFFF5F4F1.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = labels[((h ushr 3) % labels.size).toInt()]
        val wedge = if (label == 0xFFD9C8A9.toInt() || label == 0xFFFFFFFF.toInt()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        ShareCard.drawRecord(c, SIZE * 0.47f, SIZE * 0.53f, SIZE * 0.38f, ((h ushr 7) % 360).toFloat(), skin, p, true, label, wedge)
        brand(context, c)
        return bmp
    }

    fun renderFolder(context: Context, kind: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFF5F4F1.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // black module with a white sign, red dot like the OP-1 record key
        val s = SIZE.toFloat()
        val box = RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f)
        p.color = 0xFF000000.toInt()
        c.drawRoundRect(box, s * 0.08f, s * 0.08f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.strokeCap = Paint.Cap.ROUND
        val cx = s / 2f
        val cy = s / 2f
        val u = s * 0.012f
        when (kind) {
            "favorites" -> {
                val path = Path()
                path.moveTo(cx, cy + 12 * u)
                path.cubicTo(cx - 22 * u, cy - 2 * u, cx - 12 * u, cy - 16 * u, cx, cy - 7 * u)
                path.cubicTo(cx + 12 * u, cy - 16 * u, cx + 22 * u, cy - 2 * u, cx, cy + 12 * u)
                p.color = 0xFFF01E14.toInt(); c.drawPath(path, p)
            }
            "most" -> {
                listOf(-10f to 8f, 0f to 15f, 10f to 4f).forEach { (dx, hgt) ->
                    c.drawRoundRect(RectF(cx + dx * u - 3.5f * u, cy + 10 * u - hgt * u, cx + dx * u + 3.5f * u, cy + 10 * u), u, u, p)
                }
            }
            "history" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * u
                c.drawCircle(cx, cy, 13 * u, p)
                c.drawLine(cx, cy, cx, cy - 8 * u, p)
                c.drawLine(cx, cy, cx + 6 * u, cy + 3 * u, p)
                p.style = Paint.Style.FILL
            }
            "lists", "queue" -> {
                for (i in 0 until 3) {
                    val y = cy - 9 * u + i * 9 * u
                    c.drawCircle(cx - 12 * u, y, 2 * u, p)
                    c.drawRoundRect(RectF(cx - 7 * u, y - 1.4f * u, cx + 14 * u, y + 1.4f * u), u, u, p)
                }
            }
            else -> {   // all songs: a small record
                c.drawCircle(cx, cy, 15 * u, p)
                p.color = 0xFF000000.toInt(); c.drawCircle(cx, cy, 5 * u, p)
                p.color = 0xFFF01E14.toInt(); c.drawCircle(cx, cy, 4 * u, p)
            }
        }
        p.color = 0xFFF01E14.toInt()
        c.drawCircle(box.right - s * 0.07f, box.top + s * 0.07f, s * 0.022f, p)
        brand(context, c)
        return bmp
    }

    private fun brand(context: Context, c: Canvas) {
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.klang_bold) ?: Typeface.DEFAULT_BOLD
            textSize = SIZE * 0.045f
            color = 0xFF000000.toInt()
        }
        c.drawText("klang audio", SIZE * 0.06f, SIZE * 0.09f, t)
    }

    /** Draws the cover once and keeps it in the cache. */
    @Synchronized
    fun file(context: Context, uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size < 2) return null
        val dir = File(context.cacheDir, "art").apply { mkdirs() }
        val f = File(dir, "${seg[0]}-${seg[1].replace(Regex("[^A-Za-z0-9_-]"), "_")}-v2.png")
        if (f.exists() && f.length() > 0) return f
        val bmp = when (seg[0]) {
            "song" -> renderSong(context, seg[1])
            "folder" -> renderFolder(context, seg[1])
            else -> return null
        }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}

/** Serves [Covers] to Android Auto and the system media controls. Read only, images only. */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val f = Covers.file(ctx, uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
