package app.klang.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import app.klang.MainActivity
import app.klang.R

/**
 * Home-screen turntable. While music plays the record spins (12 frames flipped by the
 * launcher itself, so no battery-hungry updates) and the tonearm sits on the record.
 */
class KlangWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context, manager, ids)
    }

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
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val playing = p.getBoolean("w_p", false)
            val title = p.getString("w_t", null)?.takeIf { it.isNotBlank() }
            val artist = p.getString("w_a", "").orEmpty()
            val skin = p.getString("skin", "studio")
            val dark = skin == "graphite"

            val v = RemoteViews(context.packageName, if (playing) R.layout.widget_klang_playing else R.layout.widget_klang_paused)
            val k = when (skin) { "graphite", "sage", "walnut" -> skin; else -> "studio" }
            fun d(name: String) = context.resources.getIdentifier("w_${name}_$k", "drawable", context.packageName)
            v.setInt(R.id.w_root, "setBackgroundResource", d("bg"))
            v.setInt(R.id.w_deck, "setBackgroundResource", d("tile"))
            v.setInt(R.id.w_info, "setBackgroundResource", d("lcd"))
            v.setInt(R.id.w_track, "setBackgroundResource", d("track"))
            v.setInt(R.id.w_prev, "setBackgroundResource", d("key"))
            v.setInt(R.id.w_next, "setBackgroundResource", d("key"))
            v.setImageViewResource(R.id.w_prev, if (dark) R.drawable.w_ic_prev_light else R.drawable.w_ic_prev)
            v.setImageViewResource(R.id.w_next, if (dark) R.drawable.w_ic_next_light else R.drawable.w_ic_next)

            // colours of the housing text and of the LCD text, per skin (same as the app)
            val ink = if (dark) 0xFFECEAE4.toInt() else 0xFF232321.toInt()
            val ink2 = if (dark) 0xFFADAAA2.toInt() else 0xFF5E5C57.toInt()
            val seg = when (k) { "graphite" -> 0xFFF4AE45; "sage" -> 0xFF222720; "walnut" -> 0xFF2A231B; else -> 0xFF252523 }.toInt()
            val dim = when (k) { "graphite" -> 0xFFA48A58; "sage" -> 0xFF4C5549; "walnut" -> 0xFF6B5D43; else -> 0xFF555852 }.toInt()
            v.setTextColor(R.id.w_brand, ink)
            v.setTextColor(R.id.w_model, ink2)
            v.setTextColor(R.id.w_label, ink2)
            v.setTextColor(R.id.w_title, seg)
            v.setTextColor(R.id.w_artist, dim)

            // tidy YouTube names: "Song (Official MV)" -> "Song"
            val (cleanArtist, cleanTitle) = if (title != null) app.klang.lyrics.LrcLib.clean(title, artist) else ("" to "")
            v.setTextViewText(R.id.w_title, if (title == null) "Klang" else cleanTitle.ifBlank { title })
            v.setTextViewText(R.id.w_artist, if (title == null) "press play to start" else cleanArtist.ifBlank { artist })
            v.setTextViewText(R.id.w_label, if (playing) "playing" else "paused")
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
            manager.updateAppWidget(ids, v)
        }

        /** Same route as headphone buttons: Media3 starts playback (resuming the last queue if needed). */
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
