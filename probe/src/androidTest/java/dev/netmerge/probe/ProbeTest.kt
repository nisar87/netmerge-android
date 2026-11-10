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
        val url = args.getString("url") ?: "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb"
        val size = args.getString("size")?.toLong() ?: 140_000_000L
        val conns = args.getString("conns")?.toInt() ?: 16
        val secs = args.getString("secs")?.toDouble() ?: 30.0
        val piece = 2L shl 20
        val next = AtomicLong(0)
        val got = AtomicLong(0)
        val errors = AtomicLong(0)
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + (secs * 1000).toLong()
        val workers = List(conns) {
            thread {
                val buf = ByteArray(64 shl 10)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val start = next.getAndAdd(piece)
                    if (start >= size) break
                    try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 10_000
                        c.readTimeout = 10_000
                        c.setRequestProperty("Range", "bytes=$start-${minOf(start + piece, size) - 1}")
                        c.inputStream.use { inp ->
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                got.addAndGet(n.toLong())
                                if (SystemClock.elapsedRealtime() > deadline) break
                            }
                        }
                        c.disconnect()
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
        }
        workers.forEach { it.join() }
        val s = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val mbps = got.get() * 8 / s / 1e6
        report("download", "%.1f Mbps, %.0f MB in %.1f s with %d connections, %d errors".format(mbps, got.get() / 1e6, s, conns, errors.get()))
        assertTrue("download failed", got.get() > 0)
    }
}
