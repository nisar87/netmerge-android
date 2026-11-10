package dev.netmerge.android

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Downloads one file in pieces over several connections, each connection on a chosen network, and measures how fast
 * it went. This is the gain merging can give on this phone and carrier, before any engine or VPN is involved.
 * The data is thrown away; nothing is saved.
 */
class SplitDownload(
    private val url: String,
    private val size: Long,
    private val pieceSize: Long = 2L shl 20,
) {
    data class Result(val label: String, val seconds: Double, val bytes: Map<String, Long>, val mbps: Double, val errors: Int) {
        override fun toString(): String =
            "$label: %.1f Mbps in %.1f s (%s)%s".format(mbps, seconds,
                bytes.entries.joinToString { "${it.key} %.0f MB".format(it.value / 1e6) },
                if (errors > 0) ", $errors errors" else "")
    }

    /**
     * Runs connections[i] = (name, network) until the file is done or [maxSeconds] passed (to cap mobile data).
     * Pieces are handed out one at a time, so a fast network simply takes more of them.
     */
    fun run(label: String, connections: List<Pair<String, Network>>, maxSeconds: Double): Result {
        val next = AtomicLong(0)
        val perNet = connections.map { it.first }.distinct().associateWith { AtomicLong(0) }
        val errors = AtomicLong(0)
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + (maxSeconds * 1000).toLong()
        val workers = connections.map { (name, net) ->
            thread(name = "dl-$name") {
                val buf = ByteArray(64 shl 10)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val start = next.getAndAdd(pieceSize)
                    if (start >= size) break
                    val end = minOf(start + pieceSize, size) - 1
                    try {
                        val c = net.openConnection(URL(url)) as HttpURLConnection
                        c.connectTimeout = 8000
                        c.readTimeout = 8000
                        c.setRequestProperty("Range", "bytes=$start-$end")
                        c.inputStream.use { inp ->
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                perNet[name]!!.addAndGet(n.toLong())
                                if (SystemClock.elapsedRealtime() > deadline) break
                            }
                        }
                        c.disconnect()
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        Log.w(TAG, "$label/$name piece $start: $e")
                    }
                }
            }
        }
        workers.forEach { it.join() }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val total = perNet.values.sumOf { it.get() }
        return Result(label, secs, perNet.mapValues { it.value.get() }, total * 8 / secs / 1e6, errors.get().toInt())
            .also { Log.i(TAG, "SPEED $it") }
    }
}
