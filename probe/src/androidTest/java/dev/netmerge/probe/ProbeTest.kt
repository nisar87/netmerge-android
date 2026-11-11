package dev.netmerge.probe

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Behaves like an ordinary app: plain connections on the phone's normal route (the merging VPN when it is on).
 *   am instrument -w -e class dev.netmerge.probe.ProbeTest#<test> [-e conns 16 -e secs 30 -e url ...] \
 *     dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ProbeTest {
    private val args = InstrumentationRegistry.getArguments()

    private fun report(key: String, value: String) {
        Log.i("NetMergeProbe", "$key $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
    }

    /** Pages open: a few short requests, IPv4 and a name lookup, like browsing. */
    @Test
    fun pagesOpen() {
        for (u in listOf("http://connectivitycheck.gstatic.com/generate_204", "http://www.gstatic.com/generate_204", "https://www.google.com/")) {
            val t0 = SystemClock.elapsedRealtime()
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val code = c.responseCode
            c.inputStream.use { it.readBytes() }
            report("page", "$u -> $code in ${SystemClock.elapsedRealtime() - t0} ms")
            assertTrue("$u answered $code", code in 200..399)
        }
    }

    /** One file over many connections, like a download manager, an app store or a browser loading many files. */
    @Test
    fun download() {
        val r = Downloader.run(
            url = args.getString("url") ?: Downloader.URL,
            size = args.getString("size")?.toLong() ?: 140_000_000L,
            conns = args.getString("conns")?.toInt() ?: 16,
            secs = args.getString("secs")?.toDouble() ?: 30.0,
        )
        report("download", "%.1f Mbps, %.0f MB in %.1f s with %d connections, %d errors, longest stall %.1f s".format(r.mbps, r.mb, r.seconds, r.conns, r.errors, r.gap))
        assertTrue("download failed", r.mb > 0)
    }

    /** -e blocked a,b -e open c,d: names that must (not) resolve to a real address through the VPN's DNS. */
    @Test
    fun dnsBlocking() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("runs only with -e blocked", args.getString("blocked") != null)
        fun real(name: String): Boolean = try {
            java.net.InetAddress.getAllByName(name).any { !it.isAnyLocalAddress && !it.isLoopbackAddress }
        } catch (e: java.net.UnknownHostException) {
            false
        }
        val report = StringBuilder()
        var bad = 0
        for (n in args.getString("blocked")!!.split(",").filter { it.isNotBlank() }) {
            val r = real(n); if (r) bad++
            report.append("$n=${if (r) "RESOLVED" else "blocked"} ")
        }
        for (n in (args.getString("open") ?: "").split(",").filter { it.isNotBlank() }) {
            val r = real(n); if (!r) bad++
            report.append("$n=${if (r) "resolved" else "NOT RESOLVED"} ")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("dns", report.toString()) })
        org.junit.Assert.assertEquals(report.toString(), 0, bad)
    }

    /**
     * Upload as a speed test does (-e upload 1 -e conns 8 -e secs 15): what the app manages to write, per second.
     * "peak" far above "average" in the first seconds means data was taken in faster than it left the phone.
     */
    @Test
    fun upload() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("runs only with -e upload 1", args.getString("upload") != null)
        val conns = args.getString("conns")?.toInt() ?: 8
        val secs = args.getString("secs")?.toInt() ?: 15
        val url = args.getString("url") ?: "https://speed.cloudflare.com/__up"
        val t0 = SystemClock.elapsedRealtime()
        val perSecond = java.util.concurrent.atomic.AtomicLongArray(secs + 2)
        val errors = AtomicLong()
        val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
        val workers = (1..conns).map {
            thread {
                while (SystemClock.elapsedRealtime() - t0 < secs * 1000L) {
                    try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.requestMethod = "POST"
                        c.doOutput = true
                        c.connectTimeout = 10_000
                        c.readTimeout = 20_000
                        c.setFixedLengthStreamingMode(25L shl 20) // 25 MB per request
                        c.setRequestProperty("Content-Type", "application/octet-stream")
                        c.outputStream.use { out ->
                            var sent = 0L
                            while (sent < (25L shl 20) && SystemClock.elapsedRealtime() - t0 < secs * 1000L) {
                                out.write(chunk)
                                sent += chunk.size
                                val s = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt().coerceAtMost(secs + 1)
                                perSecond.addAndGet(s, chunk.size.toLong())
                            }
                        }
                        runCatching { c.responseCode }
                        c.disconnect()
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
        }
        workers.forEach { it.join(((secs + 30) * 1000).toLong()) }
        val rates = (0 until secs).map { perSecond.get(it) * 8 / 1e6 }
        val avg = rates.drop(2).average()
        val report = "upload=average %.1f Mbps (after the first 2 s), first second %.1f, peak %.1f, errors %d; per second: %s".format(
            avg, rates.first(), rates.maxOrNull() ?: 0.0, errors.get(), rates.joinToString(" ") { "%.0f".format(it) })
        Log.i("NetMergeProbe", report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("upload", report) })
    }
}
