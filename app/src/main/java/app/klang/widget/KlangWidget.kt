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
            val pal = when (k) {
                "graphite" -> Palette(0xFFECEAE4.toInt(), 0xFFADAAA2.toInt(), 0xFF6F6D67.toInt(), 0xFFF4AE45.toInt(), 0xFFA48A58.toInt(), 0xFFF05A1A.toInt())
                "sage" -> Palette(0xFF1E231F.toInt(), 0xFF4F5750.toInt(), 0xFF89928A.toInt(), 0xFF222720.toInt(), 0xFF4C5549.toInt(), 0xFFF05A1A.toInt())
                "walnut" -> Palette(0xFF29231C.toInt(), 0xFF615646.toInt(), 0xFFA19581.toInt(), 0xFF2A231B.toInt(), 0xFF6B5D43.toInt(), 0xFFF05A1A.toInt())
                else -> Palette(0xFF232321.toInt(), 0xFF5E5C57.toInt(), 0xFF9F9D96.toInt(), 0xFF252523.toInt(), 0xFF555852.toInt(), 0xFFF05A1A.toInt())
            }

            // ---- real size of this widget (portrait: min width, max height)
            val wDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: 300
            val hDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: 130
            val showHeader = hDp >= 116                              // very short widgets drop the header line
            val content = hDp - 9 - 11 - 6 - (if (showHeader) 18 else 0)
            val deck = content.coerceIn(56, 132)
            val keys = if (content < 96) 32 else 36
            val lcdH = (content - 5 - keys).coerceAtLeast(26)
            val rightW = (wDp - 24 - deck - 8).coerceAtLeast(90)

            val v = RemoteViews(context.packageName, if (playing) R.layout.widget_klang_playing else R.layout.widget_klang_paused)
            if (Build.VERSION.SDK_INT >= 31) {
                v.setViewLayoutWidth(R.id.w_deck, deck.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(R.id.w_deck, deck.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(R.id.w_track, keys.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }

            fun d(name: String) = context.resources.getIdentifier("w_${name}_$k", "drawable", context.packageName)
            v.setInt(R.id.w_root, "setBackgroundResource", d("bg"))
            v.setInt(R.id.w_deck, "setBackgroundResource", d("tile"))
            v.setInt(R.id.w_info, "setBackgroundResource", d("lcd"))
            v.setInt(R.id.w_track, "setBackgroundResource", d("track"))
            v.setInt(R.id.w_prev, "setBackgroundResource", d("key"))
            v.setInt(R.id.w_next, "setBackgroundResource", d("key"))
            v.setImageViewResource(R.id.w_prev, if (dark) R.drawable.w_ic_prev_light else R.drawable.w_ic_prev)
            v.setImageViewResource(R.id.w_next, if (dark) R.drawable.w_ic_next_light else R.drawable.w_ic_next)

            // ---- text, drawn in IBM Plex Sans Thai
            val (cleanArtist, cleanTitle) = if (rawTitle != null) app.klang.lyrics.LrcLib.clean(rawTitle, rawArtist) else ("" to "")
            val title = if (rawTitle == null) "Klang" else cleanTitle.ifBlank { rawTitle }
            val artist = if (rawTitle == null) "press play to start" else cleanArtist.ifBlank { rawArtist }
            val dm = context.resources.displayMetrics
            val px = { dp: Float -> dp * dm.density }
            val fonts = Fonts(context)

            v.setViewVisibility(R.id.w_head, if (showHeader) android.view.View.VISIBLE else android.view.View.GONE)
            v.setImageViewBitmap(R.id.w_head, header(fonts, pal, playing, px(wDp - 24f).toInt(), px(18f).toInt(), px))
            v.setContentDescription(R.id.w_head, "Klang turntable, ${if (playing) "playing" else "paused"}")
            // LCD inner area: bezel 4dp each side, padding 14 + 12 across, 5 + 5 down
            val textW = px(rightW - 8f - 26f).toInt().coerceAtLeast(40)
            val textH = px(lcdH - 8f - 10f).toInt().coerceAtLeast(20)
            v.setImageViewBitmap(R.id.w_text, lcdText(fonts, pal, title, artist, textW, textH, px))
            v.setContentDescription(R.id.w_text, "$title, $artist")

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
            v.setOnClickPendingIntent(R.id.w_head, open)
            return v
        }

        private class Fonts(context: Context) {
            val regular: Typeface = ResourcesCompat.getFont(context, R.font.plex_thai_regular) ?: Typeface.DEFAULT
            val medium: Typeface = ResourcesCompat.getFont(context, R.font.plex_thai_medium) ?: Typeface.DEFAULT
            val semi: Typeface = ResourcesCompat.getFont(context, R.font.plex_thai_semibold) ?: Typeface.DEFAULT_BOLD
            val bold: Typeface = ResourcesCompat.getFont(context, R.font.plex_thai_bold) ?: Typeface.DEFAULT_BOLD
            val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
        private fun lcdText(f: Fonts, pal: Palette, title: String, artist: String, w: Int, h: Int, px: (Float) -> Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val thai = { s: String -> s.any { it in '฀'..'๿' } }
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (thai(title)) f.semi else f.mono; textSize = sp(px, 13.5f); color = pal.seg
            }
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (thai(artist)) f.regular else Typeface.MONOSPACE; textSize = sp(px, 11f); color = pal.dim
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
