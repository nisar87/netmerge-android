package dev.netmerge.android

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad and tracker blocking, as in NetMerge for Windows: community block lists (HaGeZi) are downloaded once a day and
 * the engine answers questions for the names on them with "no address", so ads and trackers never load. Works while
 * NetMerge is on.
 */
object Blocklists {
    data class Pack(val id: String, val name: String, val about: String, val urls: List<String>)

    val packs = listOf(
        Pack("ads", "Ads & trackers", "A large, well kept list of ad and tracking sites (HaGeZi Light, about 90,000 sites).",
            listOf("https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/light-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt")),
        Pack("pro", "Stronger: more trackers and annoyances", "A bigger list (HaGeZi Pro, about 220,000 sites). May break a few sites; allow them from Recent blocks.",
            listOf("https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/pro-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt")),
        Pack("malware", "Malware & phishing", "Sites used for malware, phishing and scams (HaGeZi Threat Intelligence Mini).",
            listOf("https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/tif.mini-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt")),
    )

    private const val TAG = "NetMergeBlock"
    private const val DAY = 24 * 3600 * 1000L

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun on(c: Context) = p(c).getBoolean("blocking", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("blocking", v).apply()
    fun packOn(c: Context, id: String) = p(c).getBoolean("pack-$id", id == "ads" || id == "malware")
    fun setPack(c: Context, id: String, v: Boolean) = p(c).edit().putBoolean("pack-$id", v).apply()
    fun allowed(c: Context): Set<String> = p(c).getStringSet("allowed", emptySet()) ?: emptySet()
    fun allow(c: Context, name: String, v: Boolean) {
        val s = allowed(c).toMutableSet()
        if (v) s += name else s -= name
        p(c).edit().putStringSet("allowed", s).apply()
    }

    private fun file(c: Context, id: String) = File(File(c.filesDir, "blocklists").apply { mkdirs() }, "$id.txt")
    fun updated(c: Context, id: String): Long = file(c, id).let { if (it.exists()) it.lastModified() else 0 }

    /** Downloads the chosen lists that are missing or a day old (force: all chosen). Runs on a background thread. */
    fun refresh(c: Context, force: Boolean = false): Boolean {
        var changed = false
        for (pk in packs) {
            if (!packOn(c, pk.id)) continue
            val f = file(c, pk.id)
            if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < DAY) continue
            for (u in pk.urls) {
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    if (conn.responseCode != 200) continue
                    val tmp = File(f.parentFile, "${pk.id}.tmp")
                    conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    if (tmp.length() < 1000) { tmp.delete(); continue } // not a list
                    tmp.renameTo(f)
                    changed = true
                    Log.i(TAG, "${pk.id} downloaded: ${f.length()} bytes")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "${pk.id} from $u: $e")
                }
            }
        }
        return changed
    }

    /** Gives the engine the chosen lists, the allowed names and on/off. Returns the number of blocked names. */
    fun apply(c: Context): Int {
        val paths = packs.filter { packOn(c, it.id) }.map { file(c, it.id) }.filter { it.exists() }.joinToString("\n") { it.path }
        val n = mobile.Mobile.loadBlocklists(paths).toInt()
        mobile.Mobile.setAllowed(allowed(c).joinToString("\n"))
        mobile.Mobile.setBlocking(on(c))
        Log.i(TAG, "blocking ${if (on(c)) "on" else "off"}: $n names")
        return n
    }
}
