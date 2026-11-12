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
    val mbps: Double,       // speed right now, download and upload together
    val part: Double,       // part of the traffic right now (0..1)
    val share: Double,      // part of new connections it gets
    val down: Double,       // download speed right now (Mbps)
    val up: Double,         // upload speed right now (Mbps)
)

/** Everything the screen shows, read once a second. */
data class Live(
    val running: Boolean = false,
    val nets: List<NetLive> = listOf(NetLive("wifi", false, false, 0.0, 0.0, 0.0, 0.0, 0.0), NetLive("mobile", false, false, 0.0, 0.0, 0.0, 0.0, 0.0)),
    val totalMbps: Double = 0.0,
    val totalDown: Double = 0.0,
    val totalUp: Double = 0.0,
    val history: List<Double> = emptyList(), // download speed, last 60 s
    val upHistory: List<Double> = emptyList(), // upload speed, last 60 s
    val netHistory: Map<String, List<Double>> = emptyMap(), // download speed per network, last 60 s
    val peak: Double = 0.0,      // highest download speed since merging started (Mbps)
    val sessionBytes: Long = 0,  // downloaded and uploaded since merging started
    val runningSecs: Long = 0,   // how long merging has run
    val mobileToday: Long = 0,
    val overLimit: Boolean = false,
    val blockOn: Boolean = true,
    val blockNames: Int = 0,
    val blocked: Long = 0,
    val asked: Long = 0,
    val recent: List<Pair<String, Int>> = emptyList(), // name, times
    val blockedToday: Long = 0,
    val problem: String? = null, // why merging stopped by itself
    val mobileDataOn: Boolean = true, // mobile data switched on in the phone's settings (it may still be asleep on Wi-Fi)
    val paused: Boolean = false, // paused until the battery charges back
    val battery: Int = -1,       // battery level when paused
    val resumeAt: Int = 0,       // the level merging starts again at
)

/** Reads the engine every second and works out speeds from its byte counters. */
class LiveReader(private val ctx: Context) {
    private class Seen(val rx: Long, val tx: Long, val at: Long)
    private val last = HashMap<String, Seen>()
    private val history = ArrayDeque<Double>()
    private val upHistory = ArrayDeque<Double>()
    private val netHist = HashMap<String, ArrayDeque<Double>>()
    private var peak = 0.0
    private var session = 0L

    fun read(): Live {
        val running = MergeVpnService.running
        val up = upNow(ctx)
        val now = SystemClock.elapsedRealtime()
        val downs = HashMap<String, Double>()
        val ups = HashMap<String, Double>()
        val shares = HashMap<String, Double>()
        if (running) {
            val stats = runCatching { JSONObject(mobile.Mobile.stats()) }.getOrNull()
            val nets = stats?.optJSONArray("nets")
            for (i in 0 until (nets?.length() ?: 0)) {
                val n = nets!!.getJSONObject(i)
                val name = n.getString("name")
                // rx: what came in on this network (downloads), tx: what went out (uploads)
                val rx = n.getLong("rx")
                val tx = n.getLong("tx")
                val prev = last[name]
                val ok = prev != null && now > prev.at && rx >= prev.rx && tx >= prev.tx
                val secs = if (prev != null) (now - prev.at) / 1000.0 else 1.0
                downs[name] = if (ok) (rx - prev!!.rx) * 8.0 / secs / 1e6 else 0.0
                ups[name] = if (ok) (tx - prev!!.tx) * 8.0 / secs / 1e6 else 0.0
                last[name] = Seen(rx, tx, now)
                shares[name] = n.optDouble("share", 0.0)
            }
        } else {
            last.clear()
        }
        val totalDown = downs.values.sum()
        val totalUp = ups.values.sum()
        val total = totalDown + totalUp
        if (running) {
            history.addLast(totalDown); upHistory.addLast(totalUp)
            while (history.size > 60) history.removeFirst()
            while (upHistory.size > 60) upHistory.removeFirst()
            for (name in listOf("wifi", "mobile")) {
                val h = netHist.getOrPut(name) { ArrayDeque() }
                h.addLast(downs[name] ?: 0.0)
                while (h.size > 60) h.removeFirst()
            }
            if (totalDown > peak) peak = totalDown
            session = last.values.sumOf { it.rx + it.tx }
        } else { history.clear(); upHistory.clear(); netHist.clear(); peak = 0.0; session = 0 }
        val nets = listOf("wifi", "mobile").map { name ->
            val d = downs[name]
            val u = ups[name] ?: 0.0
            val s = if (d == null) null else d + u
            NetLive(name, name in up, d != null, s ?: 0.0, if (total > 0.05 && s != null) s / total else 0.0, shares[name] ?: 0.0, d ?: 0.0, u)
        }
        val b = runCatching { JSONObject(mobile.Mobile.blockStats()) }.getOrNull()
        val recent = b?.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r -> r.getString("name") to r.getInt("count") } } } ?: emptyList()
        return Live(
            running = running, nets = nets, totalMbps = total, totalDown = totalDown, totalUp = totalUp,
            history = history.toList(), upHistory = upHistory.toList(),
            netHistory = netHist.mapValues { it.value.toList() }, peak = peak, sessionBytes = session,
            runningSecs = if (running && MergeVpnService.startedAt > 0) (now - MergeVpnService.startedAt) / 1000 else 0,
            mobileToday = Usage.mobileToday(ctx), overLimit = MergeVpnService.overLimit,
            blockOn = Blocklists.on(ctx), blockNames = b?.optInt("names") ?: 0,
            blocked = b?.optLong("blocked") ?: 0, asked = b?.optLong("asked") ?: 0, recent = recent,
            blockedToday = Usage.blockedToday(ctx), problem = if (running) null else MergeVpnService.problem,
            mobileDataOn = mobileDataOn(ctx),
            paused = !running && MergeVpnService.batteryPaused, battery = MergeVpnService.batteryLevel,
            resumeAt = MergeVpnService.resumeLevel(ctx),
        )
    }

    companion object {
        /** Mobile data switched on in the phone's settings. Many phones keep it asleep while on Wi-Fi, so "not
         *  connected" alone does not mean it is off: merging wakes it. */
        fun mobileDataOn(ctx: Context): Boolean = runCatching {
            (ctx.getSystemService(android.telephony.TelephonyManager::class.java)).isDataEnabled
        }.getOrDefault(true)

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
