package app.klang.playback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * One equalizer per output: the car, each pair of headphones, the phone speaker. When the sound
 * moves to another output, the EQ in use is kept for the old one and the one saved for the new
 * output comes back.
 */
class OutputProfiles(private val context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("klang", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { main.postDelayed({ check() }, 400) }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { main.postDelayed({ check() }, 400) }
    }

    fun start() {
        am.registerAudioDeviceCallback(callback, main)
        check()
    }

    fun stop() { runCatching { am.unregisterAudioDeviceCallback(callback) } }

    /** The output sound goes to now, as a key and a name people know. */
    private fun current(): Pair<String, String> {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun first(vararg types: Int) = outs.firstOrNull { it.type in types }
        first(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)?.let {
            val n = it.productName?.toString()?.trim().orEmpty().ifEmpty { "Bluetooth" }
            return "bt:$n" to n
        }
        first(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.let { return "usb" to "USB audio" }
        first(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let { return "wired" to "Headphones" }
        return "speaker" to "Phone speaker"
    }

    private fun snapshot(): JSONObject = JSONObject()
        .put("on", prefs.getBoolean("eqOn", true))
        .put("preamp", prefs.getFloat("preamp", 0f).toDouble())
        .put("preset", prefs.getString("eqPreset", null) ?: JSONObject.NULL)
        .put("bands", JSONArray().apply { for (i in 0 until AudioEngine.BAND_COUNT) put(prefs.getFloat("eq$i", 0f).toDouble()) })

    private fun restore(o: JSONObject) {
        val b = o.optJSONArray("bands") ?: return
        val bands = FloatArray(AudioEngine.BAND_COUNT) { b.optDouble(it, 0.0).toFloat() }
        val preamp = o.optDouble("preamp", 0.0).toFloat()
        val on = o.optBoolean("on", true)
        prefs.edit().apply {
            bands.forEachIndexed { i, v -> putFloat("eq$i", v) }
            putFloat("preamp", preamp)
            putBoolean("eqOn", on)
            if (o.isNull("preset")) remove("eqPreset") else putString("eqPreset", o.optString("preset"))
        }.apply()
        AudioEngine.eqEnabled = on
        AudioEngine.preampDb = preamp
        AudioEngine.setAllGains(bands)   // bumps the version, so the EQ screen follows
    }

    private fun check() {
        val (key, name) = current()
        prefs.edit().putString("outputName", name).apply()
        if (!prefs.getBoolean("eqPerDevice", true)) return
        val last = prefs.getString("eqDevLast", null)
        if (last == key) return
        if (last != null) prefs.edit().putString("eqDev:$last", snapshot().toString()).apply()
        prefs.getString("eqDev:$key", null)?.let { runCatching { restore(JSONObject(it)) } }
        prefs.edit().putString("eqDevLast", key).apply()
    }
}

/**
 * Car Bluetooth (or any Bluetooth speaker) connects: carry on with the last playlist, when the
 * "play when Bluetooth connects" option is on. Android may not let an app start sound from the
 * background; then a notification offers one tap to play.
 */
class BluetoothResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
        val prefs = context.getSharedPreferences("klang", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autoPlayBt", false)) return
        if (PlaybackService.alive && PlaybackService.isPlayingNow) return
        val start = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME)
        val ok = runCatching { ContextCompat.startForegroundService(context, start) }.isSuccess
        if (!ok) offer(context)
    }

    private fun offer(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("resume", "Play when Bluetooth connects", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getForegroundService(
            context, 7, Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, "resume")
            .setSmallIcon(app.klang.R.drawable.ic_launcher_fg)
            .setContentTitle("Bluetooth connected")
            .setContentText("Tap to carry on with your music")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        nm.notify(7, n)
    }
}
