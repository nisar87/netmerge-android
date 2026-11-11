package dev.netmerge.probe

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Plain download in 2 MB pieces over many connections on the phone's normal route; the data is thrown away. */
object Downloader {
    const val URL = "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb"

    data class Result(val mbps: Double, val mb: Double, val seconds: Double, val conns: Int, val errors: Long)

    fun run(url: String = URL, size: Long = 140_000_000L, conns: Int = 16, secs: Double = 30.0): Result {
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
        return Result(got.get() * 8 / s / 1e6, got.get() / 1e6, s, conns, errors.get())
    }
}
