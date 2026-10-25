package app.klang.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import app.klang.R
import app.klang.ui.Skin
import java.io.File
import java.io.FileOutputStream

enum class CardFormat(val w: Int, val h: Int, val label: String) {
    Square(1080, 1080, "square"),
    Story(1080, 1920, "story"),
}

data class CardInfo(
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
    val source: String?,     // "youtu.be/…" or null for local files
    val eqLabel: String?,
)

/**
 * "Now playing" card in the Klang look: a little device with a grille, the LCD with the
 * song, a tuning scale showing how far into the song you are, and the orange play key.
 */
object ShareCard {

    fun render(context: Context, info: CardInfo, format: CardFormat, skin: Skin): Bitmap {
        val w = format.w.toFloat()
        val h = format.h.toFloat()
        val bmp = Bitmap.createBitmap(format.w, format.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sans = ResourcesCompat.getFont(context, R.font.plex_thai_regular) ?: Typeface.DEFAULT
        val semi = ResourcesCompat.getFont(context, R.font.plex_thai_semibold) ?: Typeface.DEFAULT_BOLD
        val bold = ResourcesCompat.getFont(context, R.font.plex_thai_bold) ?: Typeface.DEFAULT_BOLD
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // desk
        c.drawColor(skin.desk.toArgb())

        val margin = 72f
        val devW = w - margin * 2
        val devH = if (format == CardFormat.Square) h - margin * 2 else 1240f
        val left = margin
        val top = if (format == CardFormat.Square) margin else (h - devH) / 2f - 40f
        val dev = RectF(left, top, left + devW, top + devH)

        // device shadow + body
        p.color = 0x33000000
        c.drawRoundRect(RectF(dev.left, dev.top + 14f, dev.right, dev.bottom + 14f), 56f, 56f, p)
        p.color = skin.shell.toArgb()
        c.drawRoundRect(dev, 56f, 56f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = skin.line.toArgb()
        c.drawRoundRect(dev, 56f, 56f, p)
        p.style = Paint.Style.FILL

        val pad = 56f
        val inner = RectF(dev.left + pad, dev.top + pad, dev.right - pad, dev.bottom - pad)

        // header: wordmark + lamp
        val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 58f; color = skin.ink.toArgb(); letterSpacing = -0.02f }
        c.drawText("klang", inner.left, inner.top + 52f, word)
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semi; textSize = 30f; color = skin.ink2.toArgb() }
        c.drawText("now playing", inner.left + word.measureText("klang") + 24f, inner.top + 50f, small)
        p.color = skin.orange.toArgb()
        c.drawCircle(inner.right - 14f, inner.top + 38f, 14f, p)

        var y = inner.top + 100f

        // grille + LCD row
        val rowH = if (format == CardFormat.Square) 380f else 560f
        val grille = RectF(inner.left, y, inner.left + inner.width() * 0.36f, y + rowH)
        p.color = skin.tile.toArgb()
        c.drawRoundRect(grille, 36f, 36f, p)
        drawGrille(c, grille, skin, p)

        val lcd = RectF(grille.right + 32f, y, inner.right, y + rowH)
        p.color = skin.line.toArgb()
        c.drawRoundRect(lcd, 36f, 36f, p)
        val glass = RectF(lcd.left + 10f, lcd.top + 10f, lcd.right - 10f, lcd.bottom - 10f)
        p.shader = LinearGradient(0f, glass.top, 0f, glass.bottom, skin.lcd.toArgb(), skin.lcd2.toArgb(), Shader.TileMode.CLAMP)
        c.drawRoundRect(glass, 28f, 28f, p)
        p.shader = null

        val lcdPad = 32f
        val tw = (glass.width() - lcdPad * 2).toInt()
        val isThai = { s: String -> s.any { it in '฀'..'๿' } }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (isThai(info.title)) semi else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 54f; color = skin.seg.toArgb()
        }
        val status = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 28f; color = skin.lcdDim.toArgb() }
        c.drawText("▶ PLAY", glass.left + lcdPad, glass.top + lcdPad + 26f, status)
        val time = "${fmt(info.positionMs)} / ${fmt(info.durationMs)}"
        c.drawText(time, glass.right - lcdPad - status.measureText(time), glass.top + lcdPad + 26f, status)

        val titleLayout = layout(info.title.ifBlank { "untitled" }, titlePaint, tw, 3)
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (isThai(info.artist)) sans else mono; textSize = 36f; color = skin.lcdDim.toArgb()
        }
        val artistLayout = if (info.artist.isNotBlank()) layout(info.artist, artistPaint, tw, 2) else null
        val textH = titleLayout.height + (artistLayout?.height?.plus(16) ?: 0)
        var ty = glass.top + (glass.height() - textH) / 2f + 24f
        c.save(); c.translate(glass.left + lcdPad, ty); titleLayout.draw(c); c.restore()
        ty += titleLayout.height + 16
        if (artistLayout != null) { c.save(); c.translate(glass.left + lcdPad, ty); artistLayout.draw(c); c.restore() }

        y += rowH + 40f

        // tuning scale
        val scaleH = 150f
        val scale = RectF(inner.left, y, inner.right, y + scaleH)
        p.color = skin.line.toArgb()
        c.drawRoundRect(scale, 28f, 28f, p)
        val sGlass = RectF(scale.left + 8f, scale.top + 8f, scale.right - 8f, scale.bottom - 8f)
        p.shader = LinearGradient(0f, sGlass.top, 0f, sGlass.bottom, skin.glow.toArgb(), skin.glow2.toArgb(), Shader.TileMode.CLAMP)
        c.drawRoundRect(sGlass, 22f, 22f, p)
        p.shader = null
        val minutes = (info.durationMs / 60_000L).toInt().coerceIn(1, 20)
        val ticks = minutes * 6
        val sx0 = sGlass.left + 36f
        val sx1 = sGlass.right - 36f
        val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 26f; color = skin.scaleInk.toArgb() }
        for (i in 0..ticks) {
            val x = sx0 + (sx1 - sx0) * i / ticks
            val major = i % 6 == 0
            p.color = (if (major) skin.scaleInk else skin.scaleInk2).toArgb()
            p.strokeWidth = if (major) 4f else 2f
            c.drawLine(x, sGlass.top + 20f, x, sGlass.top + if (major) 64f else 44f, p)
            if (major && (minutes <= 10 || (i / 6) % 2 == 0)) {
                val t = (i / 6).toString()
                c.drawText(t, x - num.measureText(t) / 2f, sGlass.top + 100f, num)
            }
        }
        num.color = skin.scaleInk2.toArgb(); num.textSize = 22f
        c.drawText("min", sGlass.right - 36f - num.measureText("min"), sGlass.bottom - 14f, num)
        val prog = if (info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else 0f
        val nx = sx0 + (sx1 - sx0) * prog
        p.color = skin.needle.toArgb(); p.strokeWidth = 6f
        c.drawLine(nx, sGlass.top + 6f, nx, sGlass.bottom - 6f, p)

        y += scaleH + 40f

        // piano keys
        val keysH = (inner.bottom - y).coerceIn(110f, 170f)
        val keys = RectF(inner.left, y, inner.right, y + keysH)
        p.color = skin.line.toArgb()
        c.drawRoundRect(keys, 28f, 28f, p)
        val weights = floatArrayOf(1f, 1f, 1.4f, 1f, 1f, 1f)
        val total = weights.sum()
        val gap = 8f
        var kx = keys.left + gap
        val kw = keys.width() - gap * (weights.size + 1)
        weights.forEachIndexed { i, wt ->
            val r = RectF(kx, keys.top + gap, kx + kw * wt / total, keys.bottom - gap)
            val orange = i == 2
            p.color = (if (orange) skin.orangeDark else skin.keyLip).toArgb()
            c.drawRoundRect(r, 20f, 20f, p)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom - 8f,
                (if (orange) skin.orangeLight else skin.keyHi).toArgb(), (if (orange) skin.orange else skin.keyLo).toArgb(), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(r.left, r.top, r.right, r.bottom - 8f), 20f, 20f, p)
            p.shader = null
            drawKeyGlyph(c, i, r.centerX(), (r.top + r.bottom - 8f) / 2f, if (orange) android.graphics.Color.WHITE else skin.ink.toArgb(), p)
            kx = r.right + gap
        }

        // footer outside the device
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semi; textSize = 32f; color = skin.ink2.toArgb() }
        val footY = if (format == CardFormat.Square) h - 26f else dev.bottom + 110f
        val leftText = listOfNotNull(info.source, info.eqLabel?.let { "eq: $it" }).joinToString("   ·   ")
        if (format == CardFormat.Story) {
            val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 46f; color = skin.ink.toArgb() }
            val tl = layout(if (info.artist.isNotBlank()) "${info.artist} – ${info.title}" else info.title, big, (w - margin * 2).toInt(), 2, Layout.Alignment.ALIGN_CENTER)
            c.save(); c.translate(margin, dev.top - 60f - tl.height); tl.draw(c); c.restore()
            if (leftText.isNotBlank()) c.drawText(leftText, (w - foot.measureText(leftText)) / 2f, footY, foot)
            val brand = "played on klang"
            c.drawText(brand, (w - foot.measureText(brand)) / 2f, footY + 56f, foot)
        } else if (leftText.isNotBlank()) {
            foot.textSize = 26f
            c.drawText(leftText, margin + 8f, footY, foot)
        }
        return bmp
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.05f)
            .build()

    private fun drawGrille(c: Canvas, r: RectF, skin: Skin, p: Paint) {
        // slots like the speaker grille on the player face
        p.color = skin.hole.toArgb()
        val rows = 11
        val slotH = 12f
        val cx = r.centerX()
        val radius = minOf(r.width(), r.height()) / 2f - 30f
        for (i in 0 until rows) {
            val yy = r.top + 50f + (r.height() - 100f) * i / (rows - 1)
            val dy = yy - r.centerY()
            val half = kotlin.math.sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
            if (half < 20f) continue
            c.drawRoundRect(RectF(cx - half, yy - slotH / 2, cx - 10f, yy + slotH / 2), 6f, 6f, p)
            c.drawRoundRect(RectF(cx + 10f, yy - slotH / 2, cx + half, yy + slotH / 2), 6f, 6f, p)
        }
        p.color = skin.orange.toArgb()
        c.drawCircle(r.right - 34f, r.bottom - 34f, 9f, p)
    }

    private fun drawKeyGlyph(c: Canvas, i: Int, x: Float, y: Float, color: Int, p: Paint) {
        p.color = color
        val s = 18f
        val path = android.graphics.Path()
        when (i) {
            0 -> { // prev
                c.drawRect(x - s - 4f, y - s, x - s + 2f, y + s, p)
                path.moveTo(x + s, y - s); path.lineTo(x - s + 4f, y); path.lineTo(x + s, y + s); path.close()
            }
            1 -> c.drawRect(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f, p)
            2 -> { path.moveTo(x - s * 0.8f, y - s * 1.1f); path.lineTo(x + s * 1.1f, y); path.lineTo(x - s * 0.8f, y + s * 1.1f); path.close() }
            3 -> {
                c.drawRect(x + s - 2f, y - s, x + s + 4f, y + s, p)
                path.moveTo(x - s, y - s); path.lineTo(x + s - 4f, y); path.lineTo(x - s, y + s); path.close()
            }
            4 -> { c.drawRect(x - s * 0.7f, y - s, x - s * 0.15f, y + s, p); c.drawRect(x + s * 0.15f, y - s, x + s * 0.7f, y + s, p) }
            else -> {
                path.moveTo(x - s, y + s * 0.2f); path.lineTo(x, y - s); path.lineTo(x + s, y + s * 0.2f); path.close()
                c.drawRect(x - s, y + s * 0.55f, x + s, y + s, p)
            }
        }
        c.drawPath(path, p)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Writes the card to the shared cache folder and returns the file. */
    fun save(context: Context, bmp: Bitmap, format: CardFormat): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "klang-now-playing-${format.label}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
