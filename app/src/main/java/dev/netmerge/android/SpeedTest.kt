package dev.netmerge.android

import android.net.Network
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The built-in speed test: many connections at once (one connection cannot show what merging adds, and most speed test
 * apps use few), each pinned to one network. This app is left out of its own VPN, so "both together" runs connections
 * on Wi-Fi and on mobile data at the same time, shared by each network's own speed, the way the engine shares them.
 */
object SpeedTest {
    /** Download servers, in order: when one refuses (a test server limits how much one address may fetch), the next. */
    private val DOWN = listOf(
        "https://speed.cloudflare.com/__down?bytes=50000000",
        "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb",
    )
    private val downAt = java.util.concurrent.atomic.AtomicInteger(0)
    private const val UP = "https://speed.cloudflare.com/__up"
    const val CONNS = 16

    /** Where the test is now, for the screen (and a test script): a phase, its speed right now, and the results so far. */
    data class Row(val label: String, val down: Double = 0.0, val up: Double = 0.0, val error: String? = null)
    data class State(
        val running: Boolean = false,
        val phase: String = "",
        val now: Double = 0.0,          // Mbps right now
        val upload: Boolean = false,    // the phase measures upload
        val progress: Float = 0f,
        val rows: List<Row> = emptyList(),
        val mobileMB: Double = 0.0,     // mobile data the test used
        val done: Boolean = false,
    )

    private val stop = AtomicBoolean(false)
    fun cancel() = stop.set(true)

    /**
     * Runs the whole test (about 70 s with both networks) and reports each step. wifi/mobile: the networks (null when
     * not there); secs: per direction and network.
     */
    fun run(wifi: Network?, mobile: Network?, secs: Double = 8.0, report: (State) -> Unit) {
        stop.set(false)
        val rows = ArrayList<Row>()
        var mobileBytes = 0L
        var failed: String? = null // why the last measure got nothing
        val steps = listOfNotNull(wifi, mobile).size * 2 + (if (wifi != null && mobile != null) 2 else 0)
        var step = 0
        var st = State(running = true)
        fun show(s: State) { st = s; report(s) }
        fun measure(label: String, nets: List<Pair<Network, Int>>, upload: Boolean): Double {
            val r = transfer(nets, upload, secs) { now -> show(st.copy(phase = label, now = now, upload = upload, progress = (step + 0.5f) / steps)) }
            step++
            mobileBytes += mobile?.let { r.second[it] } ?: 0L // only what went over mobile data counts against the plan
            if (r.first == 0.0) failed = lastError
            return r.first
        }
        val alone = HashMap<Network, Double>()
        for ((net, name) in listOfNotNull(wifi?.let { it to "Wi-Fi" }, mobile?.let { it to "Mobile data" })) {
            if (stop.get()) break
            failed = null
            val d = measure("$name: download", listOf(net to CONNS), false)
            val u = measure("$name: upload", listOf(net to CONNS / 2), true)
            alone[net] = d
            rows += Row(name, d, u, failed?.let { (if (d == 0.0) "download" else "upload") + " failed: " + it })
            show(st.copy(rows = rows.toList(), mobileMB = mobileBytes / 1e6))
        }
        if (wifi != null && mobile != null && !stop.get()) {
            // connections shared by each network's speed alone (at least 2 each), as the engine shares new connections
            val dw = alone[wifi] ?: 0.0
            val dm = alone[mobile] ?: 0.0
            val wConns = if (dw + dm > 0) (CONNS * dw / (dw + dm)).toInt().coerceIn(2, CONNS - 2) else CONNS / 2
            val d = measure("Both together: download", listOf(wifi to wConns, mobile to CONNS - wConns), false)
            val u = measure("Both together: upload", listOf(wifi to maxOf(1, wConns / 2), mobile to maxOf(1, (CONNS - wConns) / 2)), true)
            rows += Row("Both together", d, u)
        }
        show(State(running = false, phase = if (stop.get()) "Stopped" else "Done", rows = rows.toList(), mobileMB = mobileBytes / 1e6, done = !stop.get()))
    }

    /**
     * Downloads (or uploads) on the given networks with the given number of connections each, for secs seconds;
     * calls now(Mbps) about 4 times a second. Returns (Mbps after the first 2 s, the bytes moved on these networks).
     */
    /** The last error of the last transfer (shown when a direction got nothing). */
    @Volatile var lastError: String? = null

    private fun transfer(nets: List<Pair<Network, Int>>, upload: Boolean, secs: Double, now: (Double) -> Unit): Pair<Double, Map<Network, Long>> {
        val bytes = AtomicLong()
        val perNet = nets.associate { it.first to AtomicLong() }
        lastError = null
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + ((secs + 2) * 1000).toLong() // 2 s to get going, not counted
        val chunk = ByteArray(64 shl 10) { (it * 31).toByte() }
        val workers = nets.flatMap { (net, n) ->
            List(n) {
                thread(name = "speedtest") {
                    val buf = ByteArray(64 shl 10)
                    while (SystemClock.elapsedRealtime() < deadline && !stop.get()) {
                        try {
                            val di = downAt.get()
                            val c = net.openConnection(URL(if (upload) UP else DOWN[di % DOWN.size])) as HttpURLConnection
                            c.connectTimeout = 8_000
                            c.readTimeout = 8_000
                            if (upload) {
                                c.requestMethod = "POST"
                                c.doOutput = true
                                c.setFixedLengthStreamingMode(10L shl 20)
                                c.setRequestProperty("Content-Type", "application/octet-stream")
                                c.outputStream.use { out ->
                                    var sent = 0L
                                    while (sent < (10L shl 20) && SystemClock.elapsedRealtime() < deadline && !stop.get()) {
                                        out.write(chunk); sent += chunk.size; bytes.addAndGet(chunk.size.toLong()); perNet[net]!!.addAndGet(chunk.size.toLong())
                                    }
                                }
                                runCatching { c.responseCode }
                            } else {
                                if (c.responseCode !in 200..299) {
                                    lastError = "server answered ${c.responseCode}"
                                    // refused (429 and the like): every connection moves on to the next server
                                    downAt.compareAndSet(di, di + 1)
                                    c.disconnect()
                                    SystemClock.sleep(200)
                                    continue
                                }
                                c.inputStream.use { inp ->
                                    while (SystemClock.elapsedRealtime() < deadline && !stop.get()) {
                                        val k = inp.read(buf)
                                        if (k < 0) break
                                        bytes.addAndGet(k.toLong()); perNet[net]!!.addAndGet(k.toLong())
                                    }
                                }
                            }
                            c.disconnect()
                        } catch (e: Exception) {
                            lastError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")
                            SystemClock.sleep(300)
                        }
                    }
                }
            }
        }
        // the speed right now, over the last second; the result counts from 2 s on (connections opened, speed settled)
        var lastB = 0L
        var lastT = t0
        var at2s = -1L
        var t2 = 0L
        while (SystemClock.elapsedRealtime() < deadline && !stop.get()) {
            SystemClock.sleep(250)
            val t = SystemClock.elapsedRealtime()
            val b = bytes.get()
            if (t - lastT >= 1000) {
                now((b - lastB) * 8.0 / ((t - lastT) / 1000.0) / 1e6)
                lastB = b; lastT = t
            }
            if (at2s < 0 && t - t0 >= 2000) { at2s = b; t2 = t }
        }
        val end = SystemClock.elapsedRealtime()
        val total = bytes.get()
        workers.forEach { it.join(3000) }
        val mbps = if (at2s >= 0 && end > t2) (total - at2s) * 8.0 / ((end - t2) / 1000.0) / 1e6 else 0.0
        return mbps to perNet.mapValues { it.value.get() }
    }
}
