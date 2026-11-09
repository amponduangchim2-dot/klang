package app.klang.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a new version of the app and hands it to Android's installer. */
object Updater {
    private fun dir(c: Context) = File(c.cacheDir, "update").apply { mkdirs() }
    private fun part(c: Context) = File(dir(c), "klang.apk.part")
    fun apk(c: Context) = File(dir(c), "klang.apk")

    /** GitHub sends the file from another host: follow redirects by hand so none are dropped. */
    private fun open(url: String, from: Long): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Klang")
            c.setRequestProperty("Accept", "application/octet-stream")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without address")
                c.disconnect()
                u = URL(URL(u), loc).toString()
            } else return c
        }
        throw IOException("too many redirects")
    }

    /** Downloads [url] with up to 8 tries, each carrying on from the bytes already saved. Call off the main thread. */
    fun download(context: Context, url: String, progress: (Float) -> Unit) {
        val part = part(context)
        val out = apk(context)
        out.delete()
        part.delete()
        var total = -1L
        var tries = 0
        while (true) {
            val have = part.length()
            try {
                val c = open(url, have)
                try {
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val resumed = code == 206 && have > 0
                    val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    if (!resumed) { part.delete() }
                    val start = if (resumed) have else 0L
                    if (len > 0) total = start + len
                    RandomAccessFile(part, "rw").use { f ->
                        f.seek(start)
                        val buf = ByteArray(64 * 1024)
                        var done = start
                        var lastTick = 0L
                        c.inputStream.use { inp ->
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                f.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastTick > 250) { lastTick = now; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                } finally { c.disconnect() }
                if (total > 0 && part.length() < total) throw IOException("cut off")
                break
            } catch (e: IOException) {
                if (++tries >= 8) throw e
                Thread.sleep(1500L * tries)
            }
        }
        if (total > 0 && part.length() != total) throw IOException("incomplete file")
        val head = ByteArray(2)
        part.inputStream().use { it.read(head) }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) { part.delete(); throw IOException("not an app file") }
        if (!part.renameTo(out)) throw IOException("could not save")
        progress(1f)
    }

    /** Opens Android's installer for the downloaded APK. False (and opens the permission page) when not allowed yet. */
    fun install(context: Context): Boolean {
        val f = apk(context)
        if (!f.isFile) return false
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
