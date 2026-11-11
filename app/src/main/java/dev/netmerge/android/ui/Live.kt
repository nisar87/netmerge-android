package dev.netmerge.android.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.netmerge.android.Blocklists
import dev.netmerge.android.MergeVpnService
import dev.netmerge.android.Usage
import org.json.JSONObject

/** One network as the screen shows it. */
data class NetLive(
    val name: String,       // "wifi" / "mobile"
    val connected: Boolean, // up on the phone
    val inMerge: Boolean,   // carrying merged traffic now
    val mbps: Double,       // speed right now
    val part: Double,       // part of the traffic right now (0..1)
    val share: Double,      // part of new connections it gets
)

/** Everything the screen shows, read once a second. */
data class Live(
    val running: Boolean = false,
    val nets: List<NetLive> = listOf(NetLive("wifi", false, false, 0.0, 0.0, 0.0), NetLive("mobile", false, false, 0.0, 0.0, 0.0)),
    val totalMbps: Double = 0.0,
    val history: List<Double> = emptyList(), // total speed, last 60 s
    val mobileToday: Long = 0,
    val overLimit: Boolean = false,
    val blockOn: Boolean = true,
    val blockNames: Int = 0,
    val blocked: Long = 0,
    val asked: Long = 0,
    val recent: List<Pair<String, Int>> = emptyList(), // name, times
    val blockedToday: Long = 0,
    val problem: String? = null, // why merging stopped by itself
    val paused: Boolean = false, // paused until the battery charges back
    val battery: Int = -1,       // battery level when paused
    val resumeAt: Int = 0,       // the level merging starts again at
)

/** Reads the engine every second and works out speeds from its byte counters. */
class LiveReader(private val ctx: Context) {
    private val last = HashMap<String, Pair<Long, Long>>()
    private val history = ArrayDeque<Double>()

    fun read(): Live {
        val running = MergeVpnService.running
        val up = upNow(ctx)
        val now = SystemClock.elapsedRealtime()
        val speeds = HashMap<String, Double>()
        val shares = HashMap<String, Double>()
        if (running) {
            val stats = runCatching { JSONObject(mobile.Mobile.stats()) }.getOrNull()
            val nets = stats?.optJSONArray("nets")
            for (i in 0 until (nets?.length() ?: 0)) {
                val n = nets!!.getJSONObject(i)
                val name = n.getString("name")
                val rx = n.getLong("rx") + n.getLong("tx")
                val prev = last[name]
                speeds[name] = if (prev != null && now > prev.second && rx >= prev.first)
                    (rx - prev.first) * 8.0 / ((now - prev.second) / 1000.0) / 1e6 else 0.0
                last[name] = rx to now
                shares[name] = n.optDouble("share", 0.0)
            }
        } else {
            last.clear()
        }
        val total = speeds.values.sum()
        if (running) {
            history.addLast(total)
            while (history.size > 60) history.removeFirst()
        } else history.clear()
        val nets = listOf("wifi", "mobile").map { name ->
            val s = speeds[name]
            NetLive(name, name in up, s != null, s ?: 0.0, if (total > 0.05 && s != null) s / total else 0.0, shares[name] ?: 0.0)
        }
        val b = runCatching { JSONObject(mobile.Mobile.blockStats()) }.getOrNull()
        val recent = b?.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r -> r.getString("name") to r.getInt("count") } } } ?: emptyList()
        return Live(
            running = running, nets = nets, totalMbps = total, history = history.toList(),
            mobileToday = Usage.mobileToday(ctx), overLimit = MergeVpnService.overLimit,
            blockOn = Blocklists.on(ctx), blockNames = b?.optInt("names") ?: 0,
            blocked = b?.optLong("blocked") ?: 0, asked = b?.optLong("asked") ?: 0, recent = recent,
            blockedToday = Usage.blockedToday(ctx), problem = if (running) null else MergeVpnService.problem,
            paused = !running && MergeVpnService.batteryPaused, battery = MergeVpnService.batteryLevel,
            resumeAt = MergeVpnService.resumeLevel(ctx),
        )
    }

    companion object {
        /** Which of Wi-Fi / mobile data are connected now, without asking Android to keep anything up. */
        fun upNow(ctx: Context): Set<String> {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val out = HashSet<String>()
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += "wifi"
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += "mobile"
            }
            return out
        }
    }
}

fun mbText(b: Long): String = when {
    b < 1_000_000 -> "%.0f KB".format(b / 1e3)
    b < 1_000_000_000 -> "%.0f MB".format(b / 1e6)
    else -> "%.2f GB".format(b / 1e9)
}

fun speedText(mbps: Double): String = if (mbps < 10) "%.1f".format(mbps) else "%.0f".format(mbps)
