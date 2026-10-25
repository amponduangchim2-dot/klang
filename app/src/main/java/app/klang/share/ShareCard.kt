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
 * "Now playing" card in the Klang Audio look (after the OP-1): one face plate split into
 * modules by black seams — the graphic record, the dot speaker, the black OLED display with
 * the song, the dark red tuning strip and the round keys.
 */
object ShareCard {

    private class Fonts(context: Context) {
        val regular: Typeface = ResourcesCompat.getFont(context, R.font.klang_regular) ?: Typeface.DEFAULT
        val medium: Typeface = ResourcesCompat.getFont(context, R.font.klang_medium) ?: Typeface.DEFAULT
        val semi: Typeface = ResourcesCompat.getFont(context, R.font.klang_semibold) ?: Typeface.DEFAULT_BOLD
        val bold: Typeface = ResourcesCompat.getFont(context, R.font.klang_bold) ?: Typeface.DEFAULT_BOLD
    }

    fun render(context: Context, info: CardInfo, format: CardFormat, skin: Skin): Bitmap {
        val w = format.w.toFloat()
        val h = format.h.toFloat()
        val bmp = Bitmap.createBitmap(format.w, format.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = Fonts(context)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(skin.desk.toArgb())

        val seam = 7f
        val margin = 64f
        val left = margin
        val right = w - margin
        val panelW = right - left
        val col = (panelW - seam * 5) / 4f            // four columns
        fun span(n: Int) = col * n + seam * (n - 1)
        val square = format == CardFormat.Square

        // row heights
        val rows: List<Float> = if (square) listOf(span(2), 120f, 190f) else listOf(span(4) * 0.62f, 360f, 130f, 220f)
        val panelH = rows.sum() + seam * (rows.size + 1)
        val headerH = 84f
        val top = if (square) (h - panelH - headerH) / 2f + headerH - 10f else (h - panelH) / 2f + 30f

        // header above the plate: wordmark, and what this is
        val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.bold; textSize = 54f; color = skin.ink.toArgb(); letterSpacing = -0.02f }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.medium; textSize = 30f; color = skin.ink2.toArgb() }
        c.drawText("klang audio", left + 6f, top - 34f, word)
        val np = "now playing"
        c.drawText(np, right - 6f - small.measureText(np) - 34f, top - 38f, small)
        p.color = skin.orange.toArgb()
        c.drawCircle(right - 14f, top - 48f, 11f, p)

        // the plate
        p.color = skin.seam.toArgb()
        c.drawRoundRect(RectF(left, top, right, top + panelH), 40f, 40f, p)
        fun cell(r: RectF) { p.color = skin.tile.toArgb(); c.drawRoundRect(r, 26f, 26f, p) }

        var y = top + seam
        val prog = if (info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else 0f

        if (square) {
            // row 1: record (2 cols) | display (2 cols)
            val rh = rows[0]
            val rec = RectF(left + seam, y, left + seam + span(2), y + rh)
            cell(rec)
            drawRecord(c, rec.centerX(), rec.centerY(), rh * 0.42f, 300f * prog, skin, p, info.positionMs > 0)
            val dispCell = RectF(rec.right + seam, y, right - seam, y + rh)
            cell(dispCell)
            drawDisplay(c, RectF(dispCell.left + 18f, dispCell.top + 18f, dispCell.right - 18f, dispCell.bottom - 18f), info, f, skin, big = false)
            y += rh + seam
        } else {
            // row 1: record (3 cols) | speaker (1 col)
            val rh = rows[0]
            val rec = RectF(left + seam, y, left + seam + span(3), y + rh)
            cell(rec)
            drawRecord(c, rec.centerX(), rec.centerY(), minOf(rec.width(), rh) * 0.42f, 300f * prog, skin, p, true)
            val spk = RectF(rec.right + seam, y, right - seam, y + rh)
            cell(spk)
            drawSpeaker(c, RectF(spk.left + 22f, spk.top + 22f, spk.right - 22f, spk.bottom - 22f), skin, p)
            y += rh + seam
            // row 2: display, full width
            val dh = rows[1]
            val disp = RectF(left + seam, y, right - seam, y + dh)
            cell(disp)
            drawDisplay(c, RectF(disp.left + 20f, disp.top + 20f, disp.right - 20f, disp.bottom - 20f), info, f, skin, big = true)
            y += dh + seam
        }

        // tuning strip, full width
        val th = rows[rows.size - 2]
        val tune = RectF(left + seam, y, right - seam, y + th)
        cell(tune)
        drawStrip(c, RectF(tune.left + 34f, tune.top, tune.right - 34f, tune.bottom), info, f, skin, p)
        y += th + seam

        // keys: prev, play, stop, next
        val kh = rows.last()
        for (i in 0 until 4) {
            val kx = left + seam + i * (col + seam)
            val r = RectF(kx, y, kx + col, y + kh)
            cell(r)
            drawCap(c, r.centerX(), r.centerY(), minOf(col, kh) * 0.33f, i, skin, p)
        }

        // footer: where it came from
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.medium; textSize = 28f; color = skin.ink2.toArgb() }
        val ft = listOfNotNull(info.source, info.eqLabel?.let { "eq $it" }).joinToString("   ·   ")
        if (ft.isNotBlank()) {
            val fy = top + panelH + if (square) 44f else 70f
            if (fy < h - 8f) c.drawText(ft, left + 6f, fy, foot)
        }
        return bmp
    }

    /** Flat graphic record: black disc, a few fine grooves, red label with a white wedge. */
    internal fun drawRecord(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float, skin: Skin, p: Paint, armOn: Boolean, label: Int = skin.orange.toArgb(), wedge: Int = 0xFFFFFFFF.toInt()) {
        p.style = Paint.Style.FILL
        p.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        for ((k, a) in listOf(0.91f to 0x26, 0.80f to 0x1C, 0.70f to 0x26, 0.55f to 0x1C)) {
            p.color = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
            p.strokeWidth = r * 0.009f
            c.drawCircle(cx, cy, r * k, p)
        }
        c.save(); c.rotate(angle, cx, cy)
        p.color = 0xFF3A3A3A.toInt(); p.strokeWidth = r * 0.022f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f), -40f, 50f, false, p)
        p.style = Paint.Style.FILL
        val lr = r * 0.38f
        p.color = label; c.drawCircle(cx, cy, lr, p)
        p.color = wedge; c.drawArc(RectF(cx - lr, cy - lr, cx + lr, cy + lr), -90f, 90f, true, p)
        p.color = label; c.drawCircle(cx, cy, lr * 0.3f, p)
        p.color = 0xFF000000.toInt(); c.drawCircle(cx, cy + lr * 0.67f, r * 0.025f, p)
        c.restore()
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r * 0.038f, p)
        // static light
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.016f; p.color = 0x55FFFFFF
        c.drawArc(RectF(cx - r * 0.74f, cy - r * 0.74f, cx + r * 0.74f, cy + r * 0.74f), 200f, 36f, false, p)
        p.color = 0x33FFFFFF
        c.drawArc(RectF(cx - r * 0.87f, cy - r * 0.87f, cx + r * 0.87f, cy + r * 0.87f), 24f, 28f, false, p)
        // tonearm: white pivot ringed in black, outlined arm, small head with a red dot
        val px = cx + r * 0.98f
        val py = cy - r * 0.86f
        val (hx, hy) = if (armOn) (cx + r * 0.5f) to (cy + r * 0.25f) else (cx + r * 1.02f) to (cy + r * 0.32f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF000000.toInt(); p.strokeWidth = r * 0.068f; c.drawLine(px, py, hx, hy, p)
        p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = r * 0.03f; c.drawLine(px, py, hx, hy, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(px, py, r * 0.14f, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF000000.toInt(); p.strokeWidth = r * 0.026f; c.drawCircle(px, py, r * 0.14f, p)
        p.style = Paint.Style.FILL; c.drawCircle(px, py, r * 0.045f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(hx, hy, r * 0.055f, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF000000.toInt(); p.strokeWidth = r * 0.022f; c.drawCircle(hx, hy, r * 0.055f, p)
        p.style = Paint.Style.FILL; p.color = skin.orange.toArgb(); c.drawCircle(hx, hy, r * 0.022f, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    /** OP-1 speaker: round holes in a rounded square. */
    private fun drawSpeaker(c: Canvas, r: RectF, skin: Skin, p: Paint) {
        val pitch = 30f
        val hole = 7.5f
        val cols = ((r.width()) / pitch).toInt()
        val rows = ((r.height()) / pitch).toInt()
        val x0 = r.centerX() - (cols - 1) * pitch / 2f
        val y0 = r.centerY() - (rows - 1) * pitch / 2f
        val corner = minOf(cols, rows) * 0.28f
        for (i in 0 until cols) for (j in 0 until rows) {
            val dx = maxOf(0f, maxOf(corner - i, i - (cols - 1 - corner)))
            val dy = maxOf(0f, maxOf(corner - j, j - (rows - 1 - corner)))
            if (dx * dx + dy * dy > corner * corner + 0.3f) continue
            val x = x0 + i * pitch
            val y = y0 + j * pitch
            p.color = 0xE6FFFFFF.toInt(); c.drawCircle(x + 2f, y + 2.5f, hole, p)
            p.color = skin.hole.toArgb(); c.drawCircle(x, y, hole, p)
        }
    }

    /** Black OLED glass with white text. */
    private fun drawDisplay(c: Canvas, r: RectF, info: CardInfo, f: Fonts, skin: Skin, big: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = skin.lcd.toArgb()
        c.drawRoundRect(r, 18f, 18f, p)
        val pad = if (big) 36f else 30f
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val status = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.semi; textSize = if (big) 30f else 26f; color = skin.seg.toArgb() }
        val dim = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.medium; textSize = if (big) 30f else 26f; color = skin.lcdDim.toArgb(); fontFeatureSettings = "tnum" }
        c.drawText("▶ play", inner.left, inner.top + status.textSize, status)
        val time = "${fmt(info.positionMs)} / ${fmt(info.durationMs)}"
        c.drawText(time, inner.right - dim.measureText(time), inner.top + status.textSize, dim)

        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.semi; textSize = if (big) 68f else 50f; color = skin.seg.toArgb() }
        val artistP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.regular; textSize = if (big) 40f else 32f; color = skin.lcdDim.toArgb() }
        val tw = inner.width().toInt()
        val title = layout(info.title.ifBlank { "untitled" }, titleP, tw, if (big) 2 else 3)
        val artist = if (info.artist.isNotBlank()) layout(info.artist, artistP, tw, 2) else null
        val blockH = title.height + (artist?.let { it.height + 10 } ?: 0)
        val areaTop = inner.top + status.textSize + 20f
        var ty = areaTop + ((inner.bottom - areaTop) - blockH).coerceAtLeast(0f) / 2f
        c.save(); c.translate(inner.left, ty); title.draw(c); c.restore()
        ty += title.height + 10
        if (artist != null) { c.save(); c.translate(inner.left, ty); artist.draw(c); c.restore() }
    }

    /** Slim dark red tuning strip with minute numbers and a red marker. */
    private fun drawStrip(c: Canvas, r: RectF, info: CardInfo, f: Fonts, skin: Skin, p: Paint) {
        val deep = 0xFF9E130D.toInt()
        val deepDim = 0x739E130D
        val total = (info.durationMs / 1000L).coerceAtLeast(60L)
        val prog = if (info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else 0f
        val barY = r.top + r.height() * 0.46f
        val nx = r.left + r.width() * prog
        val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.semi; textSize = 24f; fontFeatureSettings = "tnum" }
        val step = if (total > 1200) 60L else 10L
        var s = 0L
        while (s <= total) {
            val x = r.left + r.width() * s / total
            val major = s % 60L == 0L
            val half = !major && s % 30L == 0L
            val len = if (major) 24f else if (half) 15f else 9f
            p.color = if (x <= nx) deep else deepDim
            p.strokeWidth = if (major) 3.4f else 2.2f
            c.drawLine(x, barY - 8f - len, x, barY - 8f, p)
            if (major) {
                val every = if (total <= 480) 1 else if (total <= 1200) 2 else 5
                if ((s / 60L) % every == 0L) {
                    num.color = if (x <= nx) deep else deepDim
                    val t = (s / 60L).toString()
                    c.drawText(t, (x - num.measureText(t) / 2f).coerceIn(r.left - 20f, r.right), barY + 40f, num)
                }
            }
            s += step
        }
        p.color = 0x299E130D
        c.drawRoundRect(RectF(r.left, barY - 4f, r.right, barY + 4f), 4f, 4f, p)
        p.color = deep
        c.drawRoundRect(RectF(r.left, barY - 4f, maxOf(nx, r.left + 8f), barY + 4f), 4f, 4f, p)
        p.color = 0x2E000000; c.drawCircle(nx + 2f, barY + 3f, 13f, p)
        p.color = skin.needle.toArgb(); c.drawCircle(nx, barY, 13f, p)
    }

    /** Round OP-1 key cap lit from the top left; play gets the red glyph. */
    private fun drawCap(c: Canvas, cx: Float, cy: Float, r: Float, which: Int, skin: Skin, p: Paint) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx - r * 1.2f, cy - r * 1.2f, cx + r * 1.2f, cy + r * 1.2f, 0x17000000, 0xE6FFFFFF.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.2f, p)
        p.shader = null
        for (i in 3 downTo 1) { p.color = (0x0C + i * 4) shl 24; c.drawCircle(cx + r * 0.08f * i, cy + r * 0.1f * i, r * (1f + 0.04f * i), p) }
        p.color = skin.keyLip.toArgb(); c.drawCircle(cx + r * 0.05f, cy + r * 0.07f, r, p)
        p.shader = android.graphics.RadialGradient(cx - r * 0.4f, cy - r * 0.4f, r * 1.6f, skin.keyHi.toArgb(), skin.keyLo.toArgb(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.color = if (which == 1) skin.orange.toArgb() else skin.ink.toArgb()
        val g = r * 0.34f
        val path = android.graphics.Path()
        when (which) {
            0 -> { c.drawRect(cx - g * 1.05f, cy - g, cx - g * 0.68f, cy + g, p); path.moveTo(cx + g, cy - g); path.lineTo(cx - g * 0.55f, cy); path.lineTo(cx + g, cy + g); path.close() }
            1 -> { path.moveTo(cx - g * 0.7f, cy - g * 1.05f); path.lineTo(cx + g * 1.05f, cy); path.lineTo(cx - g * 0.7f, cy + g * 1.05f); path.close() }
            2 -> c.drawRect(cx - g * 0.8f, cy - g * 0.8f, cx + g * 0.8f, cy + g * 0.8f, p)
            else -> { c.drawRect(cx + g * 0.68f, cy - g, cx + g * 1.05f, cy + g, p); path.moveTo(cx - g, cy - g); path.lineTo(cx + g * 0.55f, cy); path.lineTo(cx - g, cy + g); path.close() }
        }
        c.drawPath(path, p)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.92f)
            .build()

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
