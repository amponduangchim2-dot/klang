package app.klang.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import app.klang.MainActivity
import app.klang.R

/**
 * Home-screen turntable. While music plays the record spins (12 frames flipped by the
 * launcher itself, so no battery-hungry updates) and the tonearm sits on the record.
 *
 * Launchers ignore app fonts in widgets, so the text is drawn here in IBM Plex Sans Thai
 * and handed over as pictures, sized to the widget's real dimensions.
 */
class KlangWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        push(context, manager, intArrayOf(id))
    }

    private class Palette(val ink: Int, val ink2: Int, val ink3: Int, val seg: Int, val dim: Int, val orange: Int)

    companion object {
        private const val PREFS = "klang"

        /** Called by the player whenever the song or play state changes. */
        fun update(context: Context, title: String, artist: String, playing: Boolean) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getString("w_t", null) == title && p.getString("w_a", null) == artist && p.getBoolean("w_p", false) == playing) return
            p.edit().putString("w_t", title).putString("w_a", artist).putBoolean("w_p", playing).apply()
            refresh(context)
        }

        /** Redraws all Klang widgets with the last known state (also after a skin change). */
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, KlangWidget::class.java))
            if (ids.isNotEmpty()) push(context, m, ids)
        }

        private fun push(context: Context, manager: AppWidgetManager, ids: IntArray) {
            for (id in ids) runCatching { manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id))) }
        }

        private fun build(context: Context, options: Bundle?): RemoteViews {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val playing = p.getBoolean("w_p", false) && app.klang.playback.PlaybackService.alive
            val rawTitle = p.getString("w_t", null)?.takeIf { it.isNotBlank() }
            val rawArtist = p.getString("w_a", "").orEmpty()
            val skin = p.getString("skin", "studio")
            val k = when (skin) { "graphite", "sage", "walnut" -> skin; else -> "studio" }
            val dark = k == "graphite"
            val pal = when (k) {   // ink, ink2, ink3, display text, display dim, red
                "graphite" -> Palette(0xFFECEAE4.toInt(), 0xFFADAAA2.toInt(), 0xFF6F6D67.toInt(), 0xFFFFFFFF.toInt(), 0xFF8E8E8E.toInt(), 0xFFF01E14.toInt())
                else -> Palette(0xFF000000.toInt(), 0xFF55534F.toInt(), 0xFF9A9893.toInt(), 0xFFFFFFFF.toInt(), 0xFF8E8E8E.toInt(), 0xFFF01E14.toInt())
            }

            // ---- real size of this widget (portrait: min width, max height)
            val wDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: 300
            val hDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: 130
            val inner = hDp - 4                                       // 2 dp seam all round
            val deck = minOf(inner, (wDp * 0.42f).toInt()).coerceIn(56, 150)
            val keys = (inner * 0.38f).toInt().coerceIn(38, 54)
            val lcdH = (inner - 2 - keys).coerceAtLeast(30)
            val rightW = (wDp - 4 - deck - 2).coerceAtLeast(90)

            val v = RemoteViews(context.packageName, if (playing) R.layout.widget_klang_playing else R.layout.widget_klang_paused)
            if (Build.VERSION.SDK_INT >= 31) {
                v.setViewLayoutWidth(R.id.w_deck, deck.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(R.id.w_keys, keys.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }

            fun d(name: String) = context.resources.getIdentifier("w_$name", "drawable", context.packageName)
            v.setInt(R.id.w_root, "setBackgroundResource", d("bg_$k"))
            v.setInt(R.id.w_deck, "setBackgroundResource", d("cell_${k}_tl"))
            v.setInt(R.id.w_lcdcell, "setBackgroundResource", d("cell_${k}_tr"))
            v.setInt(R.id.w_info, "setBackgroundResource", d("lcd_$k"))
            v.setInt(R.id.w_prev, "setBackgroundResource", d("cell_$k"))
            v.setInt(R.id.w_play, "setBackgroundResource", d("cell_$k"))
            v.setInt(R.id.w_next, "setBackgroundResource", d("cell_${k}_br"))
            val tone = if (dark) "dark" else "light"
            v.setImageViewResource(R.id.w_prev, d("cap_prev_$tone"))
            v.setImageViewResource(R.id.w_play, d(if (playing) "cap_pause_$tone" else "cap_play_$tone"))
            v.setImageViewResource(R.id.w_next, d("cap_next_$tone"))

            // ---- text on the display, drawn in IBM Plex Sans Thai
            val (cleanArtist, cleanTitle) = if (rawTitle != null) app.klang.lyrics.LrcLib.clean(rawTitle, rawArtist) else ("" to "")
            val title = if (rawTitle == null) "Klang Audio" else cleanTitle.ifBlank { rawTitle }
            val artist = if (rawTitle == null) "press play to start" else cleanArtist.ifBlank { rawArtist }
            val dm = context.resources.displayMetrics
            val px = { dp: Float -> dp * dm.density }
            val fonts = Fonts(context)
            // display area: cell padding 5, glass bezel 3, text padding 10 + 8 across, 5 + 5 down
            val textW = px(rightW - 10f - 6f - 18f).toInt().coerceAtLeast(40)
            val textH = px(lcdH - 10f - 6f - 10f).toInt().coerceAtLeast(20)
            v.setImageViewBitmap(R.id.w_text, lcdText(fonts, pal, playing, title, artist, textW, textH, px))
            v.setContentDescription(R.id.w_text, "Klang Audio, ${if (playing) "playing" else "paused"}: $title, $artist")

            v.setOnClickPendingIntent(R.id.w_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            v.setOnClickPendingIntent(R.id.w_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.w_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            val open = PendingIntent.getActivity(
                context, 10,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_deck, open)
            v.setOnClickPendingIntent(R.id.w_info, open)
            return v
        }

        private class Fonts(context: Context) {
            val regular: Typeface = ResourcesCompat.getFont(context, R.font.klang_regular) ?: Typeface.DEFAULT
            val medium: Typeface = ResourcesCompat.getFont(context, R.font.klang_medium) ?: Typeface.DEFAULT
            val semi: Typeface = ResourcesCompat.getFont(context, R.font.klang_semibold) ?: Typeface.DEFAULT_BOLD
            val bold: Typeface = ResourcesCompat.getFont(context, R.font.klang_bold) ?: Typeface.DEFAULT_BOLD
            val mono: Typeface get() = semi
        }

        private fun sp(px: (Float) -> Float, v: Float) = px(v)   // widget text does not follow the font-size setting, like the app

        /** "klang  turntable  ·········  playing ●" */
        private fun header(f: Fonts, pal: Palette, playing: Boolean, w: Int, h: Int, px: (Float) -> Float): Bitmap {
            val bmp = Bitmap.createBitmap(w.coerceAtLeast(10), h.coerceAtLeast(10), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val base = h * 0.76f
            val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.bold; textSize = sp(px, 14f); color = pal.ink; letterSpacing = -0.02f }
            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.medium; textSize = sp(px, 11f); color = pal.ink2 }
            var x = px(2f)
            c.drawText("klang", x, base, brand)
            x += brand.measureText("klang") + px(6f)
            c.drawText("turntable", x, base, small)
            // lamp
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val lx = w - px(6f)
            val ly = h * 0.52f
            if (playing) {
                p.color = (pal.orange and 0x00FFFFFF) or 0x30000000
                c.drawCircle(lx, ly, px(5f), p)
                p.color = pal.orange
            } else p.color = pal.ink3
            c.drawCircle(lx, ly, px(3.5f), p)
            val status = if (playing) "playing" else "paused"
            c.drawText(status, lx - px(10f) - small.measureText(status), base, small)
            return bmp
        }

        /** Title (two lines when there is room) and artist on the LCD, vertically centred. */
        private fun lcdText(f: Fonts, pal: Palette, playing: Boolean, title: String, artist: String, w: Int, fullH: Int, px: (Float) -> Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, fullH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // status line, like the app's LCD: "▶ PLAY" ... "klang"
            val st = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.mono; textSize = px(9.5f); color = pal.seg }
            val stBase = px(9.5f)
            c.drawText(if (playing) "▶ PLAY" else "❚❚ PAUSE", 0f, stBase, st)
            val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.bold; textSize = px(10.5f); color = pal.dim }
            c.drawText("klang audio", w - brand.measureText("klang audio"), stBase, brand)
            val top = px(13f)
            val h = (fullH - top).toInt().coerceAtLeast(10)
            c.translate(0f, top)
            val thai = { s: String -> s.any { it in '฀'..'๿' } }
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = f.semi; textSize = sp(px, 13.5f); color = pal.seg
            }
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = f.regular; textSize = sp(px, 11f); color = pal.dim
            }
            // Lines are placed by hand: Plex Thai reports very tall line boxes (1.65 em), which wastes the small LCD.
            // Title: two lines if there is room, else one; shrink a little before dropping the artist.
            var size = 13.5f
            var lines: List<String>
            var showArtist: Boolean
            fun titleLines(n: Int): List<String> {
                val l = layout(title, tp, w, n)
                return (0 until l.lineCount).map { i ->
                    val raw = title.substring(l.getLineStart(i), l.getLineEnd(i)).trimEnd()
                    if (i == l.lineCount - 1 && l.getEllipsisCount(i) > 0) TextUtils.ellipsize(title.substring(l.getLineStart(i)), tp, w.toFloat(), TextUtils.TruncateAt.END).toString() else raw
                }
            }
            fun blockH(n: Int, artistOn: Boolean): Float {
                val t = tp.textSize
                val a = ap.textSize
                return t * (1.0f + 1.28f * (n - 1)) + t * 0.3f + if (artistOn) a * 0.2f + a * 1.3f else 0f
            }
            while (true) {
                tp.textSize = sp(px, size)
                ap.textSize = sp(px, (size * 0.82f).coerceAtLeast(10f))
                val artistOn = artist.isNotBlank()
                val two = titleLines(2)
                lines = if (two.size > 1 && blockH(2, artistOn) <= h) two else titleLines(1)
                showArtist = artistOn && blockH(lines.size, true) <= h
                if (showArtist || !artistOn || size <= 11.5f) break
                size -= 0.5f
            }
            val total = blockH(lines.size, showArtist)
            var base = ((h - total) / 2f).coerceAtLeast(0f) + tp.textSize * 1.0f
            lines.forEachIndexed { i, line ->
                c.drawText(line, 0f, base, tp)
                if (i < lines.size - 1) base += tp.textSize * 1.28f
            }
            if (showArtist) {
                base += tp.textSize * 0.3f + ap.textSize * 0.2f + ap.textSize * 1.0f
                c.drawText(TextUtils.ellipsize(artist, ap, w.toFloat(), TextUtils.TruncateAt.END).toString(), 0f, base, ap)
            }
            return bmp
        }

        private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.8f)
                .build()

        /** Same route as headphone buttons: Media3 starts playback (resuming the last queue if needed). */
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
