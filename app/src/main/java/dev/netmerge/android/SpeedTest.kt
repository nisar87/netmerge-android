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

    /** Told once per network and test when a socket could not be bound to it (for the log; see DualNetworks.stateOf). */
    @Volatile var onBindFailed: ((Network, String) -> Unit)? = null
    private val bindTold = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    fun cancel() = stop.set(true)
    fun stopped() = stop.get()

    /**
     * Runs the whole test (about 70 s with both networks) and reports each step. wifi: Wi-Fi now (null when not
     * there); mobile: asked for only when its turn comes, and may wait for mobile data to wake (many phones keep it
     * asleep while on Wi-Fi): the Wi-Fi part no longer waits for it (it sat up to 40 s on "Waking mobile data" first,
     * which felt stuck). secs: per direction and network.
     */
    fun run(wifi: Network?, mobile: () -> Network?, secs: Double = 8.0, report: (State) -> Unit) {
        stop.set(false)
        bindTold.clear()
        val rows = ArrayList<Row>()
        var mobileBytes = 0L
        var failed: String? = null // why the last measure got nothing
        var steps = (if (wifi != null) 2 else 0) + 4 // counting on mobile data and both together; fixed when it is known
        var step = 0
        var st = State(running = true)
        fun show(s: State) { st = s; report(s) }
        var mob: Network? = null
        fun measure(label: String, nets: List<Pair<Network, Int>>, upload: Boolean): Double {
            val r = transfer(nets, upload, secs) { now, part ->
                show(st.copy(running = true, phase = label, now = now, upload = upload, progress = ((step + part) / steps).toFloat().coerceIn(0f, 1f)))
            }
            step++
            mobileBytes += mob?.let { r.second[it] } ?: 0L // only what went over mobile data counts against the plan
            if (r.first == 0.0) failed = lastError ?: if (upload) "too slow to measure" else null
            return r.first
        }
        val alone = HashMap<Network, Double>()
        fun alone(net: Network, name: String) {
            failed = null
            val d = measure("$name: download", listOf(net to CONNS), false)
            val u = measure("$name: upload", listOf(net to CONNS / 2), true)
            // stopped half way: no result for this network rather than a false "failed" (the owner stopped a test
            // and saw "download failed: too slow to measure" and "did not come up")
            if (stop.get()) return
            alone[net] = d
            rows += Row(name, d, u, failed?.let { (if (d == 0.0) "download" else "upload") + " failed: " + it })
            show(st.copy(rows = rows.toList(), mobileMB = mobileBytes / 1e6, now = 0.0))
        }
        if (wifi != null && !stop.get()) alone(wifi, "Wi-Fi")
        if (!stop.get()) {
            show(st.copy(phase = "Waking mobile data", now = 0.0, upload = false))
            mob = mobile()
        }
        val mobile = mob
        steps = (if (wifi != null) 2 else 0) + (if (mobile != null) 2 else 0) + (if (wifi != null && mobile != null) 2 else 0)
        if (mobile != null && !stop.get()) alone(mobile, "Mobile data")
        if (wifi != null && mobile != null && !stop.get()) {
            // connections shared by each network's speed alone (at least 2 each), as the engine shares new connections
            val dw = alone[wifi] ?: 0.0
            val dm = alone[mobile] ?: 0.0
            val wConns = if (dw + dm > 0) (CONNS * dw / (dw + dm)).toInt().coerceIn(2, CONNS - 2) else CONNS / 2
            val d = measure("Both together: download", listOf(wifi to wConns, mobile to CONNS - wConns), false)
            val u = measure("Both together: upload", listOf(wifi to maxOf(1, wConns / 2), mobile to maxOf(1, (CONNS - wConns) / 2)), true)
            if (!stop.get()) rows += Row("Both together", d, u)
        }
        show(State(running = false, phase = if (!stop.get()) "Done" else if (rows.isEmpty()) "Stopped before a network finished" else "Stopped: results for the networks that finished", rows = rows.toList(), mobileMB = mobileBytes / 1e6, done = !stop.get()))
    }

    /**
     * Downloads (or uploads) on the given networks with the given number of connections each, for secs seconds;
     * calls now(Mbps) about 4 times a second. Returns (Mbps after the first 2 s, the bytes moved on these networks).
     */
    /** The last error of the last transfer (shown when a direction got nothing). */
    @Volatile var lastError: String? = null

    private fun transfer(nets: List<Pair<Network, Int>>, upload: Boolean, secs: Double, now: (Double, Double) -> Unit): Pair<Double, Map<Network, Long>> {
        val bytes = AtomicLong()
        val perNet = nets.associate { it.first to AtomicLong() }
        lastError = null
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + ((secs + 2) * 1000).toLong() // 2 s to get going, not counted
        val chunk = ByteArray(64 shl 10) { (it * 31).toByte() }
        val open = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>() // uploads under way
        val workers = nets.flatMap { (net, n) ->
            List(n) {
                thread(name = "speedtest") {
                    val buf = ByteArray(64 shl 10)
                    var upSize = 256L shl 10 // one upload request's size: starts small, grows on a fast line
                    while (SystemClock.elapsedRealtime() < deadline && !stop.get()) {
                        try {
                            val di = downAt.get()
                            val c = net.openConnection(URL(if (upload) UP else DOWN[di % DOWN.size])) as HttpURLConnection
                            c.connectTimeout = 8_000
                            c.readTimeout = 8_000
                            if (upload) {
                                // an upload counts when the server has it all (its answer), not when it is handed to
                                // the phone's buffers: those take megabytes in a moment, so the old way counted fast
                                // at first and then nothing on a slow line, and a body cut short at the end raised
                                // "unexpected end of stream" (seen on a Galaxy S25 Ultra: upload 0.0 Mbps)
                                c.requestMethod = "POST"
                                c.doOutput = true
                                c.setFixedLengthStreamingMode(upSize)
                                c.setRequestProperty("Content-Type", "application/octet-stream")
                                open.add(c)
                                val began = SystemClock.elapsedRealtime()
                                c.outputStream.use { out ->
                                    var sent = 0L
                                    while (sent < upSize) {
                                        if (stop.get()) throw java.io.InterruptedIOException("stopped")
                                        val k = minOf(chunk.size.toLong(), upSize - sent).toInt()
                                        out.write(chunk, 0, k); sent += k
                                        perNet[net]!!.addAndGet(k.toLong()) // data used: all that was sent, finished or not
                                    }
                                }
                                val code = c.responseCode
                                open.remove(c)
                                if (code in 200..299) {
                                    if (SystemClock.elapsedRealtime() <= deadline) {
                                        bytes.addAndGet(upSize)
                                    }
                                    // fast line: bigger requests (fewer round trips); slow line: they stay small
                                    if (SystemClock.elapsedRealtime() - began < 1500 && upSize < (8L shl 20)) upSize *= 2
                                } else {
                                    lastError = "server answered $code"
                                    SystemClock.sleep(200)
                                }
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
                            // an upload still running at the end is cut off on purpose (below): not an error
                            if (SystemClock.elapsedRealtime() < deadline && !stop.get())
                                lastError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")
                            if (e.message?.startsWith("Binding socket") == true && bindTold.add(net))
                                runCatching { onBindFailed?.invoke(net, e.message!!.take(120)) }
                            SystemClock.sleep(300)
                        }
                    }
                }
            }
        }
        // the speed right now, shown 4 times a second over a sliding window (1 s for downloads; 2 s for uploads, which
        // are counted a request at a time); the result counts from 2 s on (connections opened, speed settled)
        val window = if (upload) 2000L else 1000L
        val samples = ArrayDeque<Pair<Long, Long>>()
        samples.addLast(t0 to 0L)
        var at2s = -1L
        var t2 = 0L
        while (SystemClock.elapsedRealtime() < deadline && !stop.get()) {
            SystemClock.sleep(250)
            val t = SystemClock.elapsedRealtime()
            val b = bytes.get()
            samples.addLast(t to b)
            while (samples.size > 2 && t - samples[1].first >= window) samples.removeFirst()
            val (ta, ba) = samples.first()
            now(if (t > ta) (b - ba) * 8.0 / ((t - ta) / 1000.0) / 1e6 else 0.0, (t - t0).toDouble() / (deadline - t0))
            if (at2s < 0 && t - t0 >= 2000) { at2s = b; t2 = t }
        }
        val end = SystemClock.elapsedRealtime()
        val total = bytes.get()
        open.forEach { runCatching { it.disconnect() } } // uploads still under way: their workers end now
        workers.forEach { it.join(3000) }
        val mbps = if (at2s >= 0 && end > t2) (total - at2s) * 8.0 / ((end - t2) / 1000.0) / 1e6 else 0.0
        return mbps to perNet.mapValues { it.value.get() }
    }
}
