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
            v.setInt(R.id.w_root, "setBackgroundResource", when (skin) {
                "graphite" -> R.drawable.w_bg_dark
                "sage" -> R.drawable.w_bg_sage
                "walnut" -> R.drawable.w_bg_walnut
                else -> R.drawable.w_bg
            })
            val ink = if (dark) 0xFFECEAE4.toInt() else 0xFF232321.toInt()
            val ink2 = if (dark) 0xFFADAAA2.toInt() else 0xFF5E5C57.toInt()
            v.setTextViewText(R.id.w_title, title ?: "Klang")
            v.setTextViewText(R.id.w_artist, if (title == null) "tap play to start" else artist)
            v.setTextViewText(R.id.w_label, if (playing) "klang · playing" else "klang · paused")
            v.setTextColor(R.id.w_title, ink)
            v.setTextColor(R.id.w_artist, ink2)
            v.setTextColor(R.id.w_label, ink2)
            val key = if (dark) R.drawable.w_key_dark else R.drawable.w_key
            v.setInt(R.id.w_prev, "setBackgroundResource", key)
            v.setInt(R.id.w_next, "setBackgroundResource", key)
            v.setImageViewResource(R.id.w_prev, if (dark) R.drawable.w_ic_prev_light else R.drawable.w_ic_prev)
            v.setImageViewResource(R.id.w_next, if (dark) R.drawable.w_ic_next_light else R.drawable.w_ic_next)

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
