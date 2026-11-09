package app.klang.web

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad blocking for the web screen, from the usual public filter lists:
 * EasyList and EasyList Thailand (network rules "||host^" and element hiding "##selector")
 * and Steven Black's unified hosts file. The lists ship inside the app (downloaded when the app
 * is built) and are refreshed on the phone once a week.
 */
object AdBlocker {
    private class Source(val name: String, val url: String)

    private val sources = listOf(
        Source("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        Source("easylist-thailand.txt", "https://raw.githubusercontent.com/easylist-thailand/easylist-thailand/master/subscription/easylist-thailand.txt"),
        Source("hosts.txt", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
    )
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    private const val MAX_GENERIC = 6000

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var genericCss: List<String> = emptyList()
    @Volatile private var siteCss: Map<String, List<String>> = emptyMap()
    @Volatile var ready = false
        private set
    val size: Int get() = blocked.size

    private val lock = Any()

    /** Reads the lists. Call off the main thread. */
    fun load(context: Context) {
        synchronized(lock) {
            if (ready) return
            parseAll(context)
            ready = true
        }
    }

    private fun parseAll(context: Context) {
        val block = HashSet<String>(150_000)
        val allow = HashSet<String>()
        val generic = LinkedHashSet<String>()
        val site = HashMap<String, MutableList<String>>()
        val updated = File(context.filesDir, "adblock")
        val texts = mutableListOf<Sequence<String>>()
        // built-in short list, always
        runCatching { context.assets.open("adblock/builtin.txt").bufferedReader().readLines() }.getOrNull()?.let { texts += it.asSequence() }
        for (s in sources) {
            val f = File(updated, s.name)
            val lines = if (f.exists() && f.length() > 1000) runCatching { f.readLines() }.getOrNull()
            else runCatching { context.assets.open("adblock/${s.name}").bufferedReader().readLines() }.getOrNull()
            if (lines != null) texts += lines.asSequence()
        }
        for (seq in texts) for (raw in seq) parseLine(raw.trim(), block, allow, generic, site)
        blocked = block
        allowed = allow
        genericCss = generic.take(MAX_GENERIC)
        siteCss = site
    }

    private val hostRe = Regex("^[a-z0-9.-]+$")

    private fun parseLine(l: String, block: MutableSet<String>, allow: MutableSet<String>, generic: MutableSet<String>, site: MutableMap<String, MutableList<String>>) {
        if (l.isEmpty() || l[0] == '!' || l[0] == '[' || l[0] == '#' && !l.startsWith("##")) return
        // hosts file: "0.0.0.0 ads.example.com"
        if (l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ")) {
            val h = l.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
            if (h != "0.0.0.0" && h != "localhost" && h.contains('.') && hostRe.matches(h)) block += h
            return
        }
        // element hiding
        val hide = l.indexOf("##")
        if (hide >= 0 && !l.contains("#@#") && !l.contains("#?#") && !l.contains("#$#")) {
            val sel = l.substring(hide + 2)
            if (sel.isEmpty() || sel.startsWith("+js") || sel.contains(":-abp-") || sel.contains(":has-text") || sel.contains("{")) return
            if (hide == 0) generic += sel
            else l.substring(0, hide).split(',').forEach { d ->
                val dom = d.trim().lowercase()
                if (dom.isNotEmpty() && !dom.startsWith("~")) site.getOrPut(dom) { mutableListOf() } += sel
            }
            return
        }
        // network: ||host^ with optional options; skip rules tied to particular sites
        val exception = l.startsWith("@@||")
        val body = when {
            exception -> l.substring(4)
            l.startsWith("||") -> l.substring(2)
            else -> return
        }
        val opts = body.substringAfter('$', "")
        if (opts.contains("domain=") || opts.contains("~third-party") || opts.contains("badfilter")) return
        val pattern = body.substringBefore('$')
        if (!pattern.endsWith("^") && !pattern.endsWith("^|")) return
        val host = pattern.trimEnd('|').removeSuffix("^").lowercase()
        if (host.contains('/') || host.contains('*') || !host.contains('.') || !hostRe.matches(host)) return
        if (exception) allow += host else block += host
    }

    private fun matches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
        }
    }

    /** True when requests to [host] (or a parent domain) are ads or trackers. */
    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty() || !ready) return false
        val h = host.lowercase()
        return matches(blocked, h) && !matches(allowed, h)
    }

    /** CSS that hides ad boxes on a page of [host]. */
    fun css(host: String?): String {
        val sels = ArrayList<String>(genericCss.size + 50)
        sels += genericCss
        var h = host?.lowercase() ?: ""
        while (h.isNotEmpty()) {
            siteCss[h]?.let { sels += it }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        // one rule per chunk, so one bad selector does not cancel the rest
        return sels.chunked(400).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }
    }

    /** Downloads fresh lists once a week. Call off the main thread. */
    fun updateIfDue(context: Context) {
        val prefs = context.getSharedPreferences("klang", Context.MODE_PRIVATE)
        val last = prefs.getLong("adblockUpdated", 0L)
        if (System.currentTimeMillis() - last < WEEK) return
        val dir = File(context.filesDir, "adblock").apply { mkdirs() }
        var any = false
        for (s in sources) {
            runCatching {
                val conn = URL(s.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                try {
                    if (conn.responseCode == 200) {
                        val tmp = File(dir, s.name + ".tmp")
                        conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        if (tmp.length() > 1000) { tmp.renameTo(File(dir, s.name)); any = true } else tmp.delete()
                    }
                } finally { conn.disconnect() }
            }
        }
        if (any) {
            prefs.edit().putLong("adblockUpdated", System.currentTimeMillis()).apply()
            synchronized(lock) { parseAll(context); ready = true }
        }
    }

    /** The site part of a host name, for "same site" checks: www.example.co.th → example.co.th. */
    fun siteOf(host: String?): String {
        val parts = host?.lowercase()?.split('.')?.filter { it.isNotEmpty() } ?: return ""
        if (parts.size <= 2) return parts.joinToString(".")
        val second = parts[parts.size - 2]
        val n = if (parts.last().length == 2 && second in setOf("co", "ac", "or", "go", "in", "net", "com", "org", "mi")) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }
}
