package app.klang.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.klang.MainActivity
import app.klang.R

/**
 * Keeps the app awake while a film plays on the TV. The TV fetches every piece of the film through
 * the phone; without this, Android freezes the app a little after it leaves the screen and the TV
 * stops (the "plays a bit, then drops out" case).
 */
class CastKeepAlive : Service() {
    companion object {
        private const val CHANNEL = "casting"
        private const val ID = 7301
        private const val STOP = "app.klang.cast.STOP"

        fun start(c: Context, title: String, tv: String) {
            val i = Intent(c, CastKeepAlive::class.java).putExtra("title", title).putExtra("tv", tv)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(c, i) }
        }

        fun stop(c: Context) {
            runCatching { c.stopService(Intent(c, CastKeepAlive::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Caster.peek()?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing on a TV", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastKeepAlive::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("on " + (intent?.getStringExtra("tv") ?: "the TV"))
            .setContentText(intent?.getStringExtra("title") ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "stop", stop)
            .build()
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        }.isSuccess
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }
}
