package app.klang.backup

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything worth keeping in one file: settings, EQ and presets, playlists, favorites, play
 * counts, history, bookmarks, saved lists, web bookmarks. A .klang file is a zip with
 * settings.json and the library files.
 */
object Backup {
    private const val SETTINGS = "settings.json"
    /** Files under the app's files folder that hold the library. */
    private val folders = listOf("playlists")
    private val files = listOf("library.json")

    /** Settings that belong to this phone only and are not carried over. */
    private val skip = setOf("adblockUpdated", "eqDevLast", "outputName")

    fun export(context: Context, target: Uri): Int {
        var count = 0
        context.contentResolver.openOutputStream(target, "w")?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                val prefs = context.getSharedPreferences("klang", Context.MODE_PRIVATE).all
                val o = JSONObject()
                for ((k, v) in prefs) {
                    if (k in skip || v == null) continue
                    val e = JSONObject()
                    when (v) {
                        is Boolean -> e.put("t", "b").put("v", v)
                        is Int -> e.put("t", "i").put("v", v)
                        is Long -> e.put("t", "l").put("v", v)
                        is Float -> e.put("t", "f").put("v", v.toDouble())
                        is String -> e.put("t", "s").put("v", v)
                        is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                        else -> continue
                    }
                    o.put(k, e); count++
                }
                zip.putNextEntry(ZipEntry(SETTINGS))
                zip.write(JSONObject().put("app", "klang audio").put("version", 1).put("prefs", o).toString().toByteArray())
                zip.closeEntry()
                val base = context.filesDir
                fun add(f: File, name: String) {
                    if (!f.isFile) return
                    zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); count++
                }
                files.forEach { add(File(base, it), it) }
                folders.forEach { d -> File(base, d).listFiles()?.forEach { add(it, "$d/${it.name}") } }
            }
        } ?: error("Could not write the file")
        return count
    }

    /** Puts a backup back. The app restarts afterwards so every screen reads it fresh. */
    fun import(context: Context, source: Uri): Int {
        var count = 0
        var sawSettings = false
        val base = context.filesDir.canonicalFile
        context.contentResolver.openInputStream(source)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory) continue
                    if (name == SETTINGS) {
                        val root = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        if (root.optString("app") != "klang audio") error("This is not a Klang Audio backup")
                        val o = root.getJSONObject("prefs")
                        val ed = context.getSharedPreferences("klang", Context.MODE_PRIVATE).edit()
                        for (k in o.keys()) {
                            if (k in skip) continue
                            val v = o.getJSONObject(k)
                            when (v.optString("t")) {
                                "b" -> ed.putBoolean(k, v.getBoolean("v"))
                                "i" -> ed.putInt(k, v.getInt("v"))
                                "l" -> ed.putLong(k, v.getLong("v"))
                                "f" -> ed.putFloat(k, v.getDouble("v").toFloat())
                                "s" -> ed.putString(k, v.getString("v"))
                                "ss" -> ed.putStringSet(k, v.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                            }
                            count++
                        }
                        ed.commit()
                        sawSettings = true
                        continue
                    }
                    // library files only, and never outside the app's own folder
                    val allowed = name in files || folders.any { name.startsWith("$it/") && !name.removePrefix("$it/").contains('/') }
                    if (!allowed) continue
                    val out = File(base, name).canonicalFile
                    if (!out.path.startsWith(base.path + File.separator)) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        } ?: error("Could not open the file")
        if (!sawSettings) error("This is not a Klang Audio backup")
        return count
    }
}
