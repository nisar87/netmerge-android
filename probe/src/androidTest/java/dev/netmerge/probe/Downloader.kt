package dev.netmerge.probe

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Plain download in 2 MB pieces over many connections on the phone's normal route; the data is thrown away. */
object Downloader {
    const val URL = "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb"

    /** gap: the longest stretch (seconds) in which nothing arrived, i.e. how long a dropout stalled the download. */
    data class Result(val mbps: Double, val mb: Double, val seconds: Double, val conns: Int, val errors: Long, val gap: Double)

    fun run(url: String = URL, size: Long = 140_000_000L, conns: Int = 16, secs: Double = 30.0): Result {
        val piece = 2L shl 20
        // size may be larger than the file: the download then goes round the file again (pieces never cross its end;
        // asking past the end would fail at once and look like a network error)
        val fileLen = fileLength(url)
        val lapLen = (fileLen + piece - 1) / piece * piece
        val next = AtomicLong(0)
        val got = AtomicLong(0)
        val errors = AtomicLong(0)
        val retry = java.util.concurrent.ConcurrentLinkedQueue<Long>() // failed pieces are fetched again, like a real download
        val t0 = SystemClock.elapsedRealtime()
        val lastByte = AtomicLong(t0)
        val gap = AtomicLong(0)
        val deadline = t0 + (secs * 1000).toLong()
        val workers = List(conns) {
            thread {
                val buf = ByteArray(64 shl 10)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val start = retry.poll() ?: next.getAndAdd(piece)
                    if (start >= size) break
                    val pieceV = start / piece * piece // where this piece starts, counted in the whole download
                    val pieceF = pieceV % lapLen // ... and in the file
                    val endF = minOf(pieceF + piece, fileLen)
                    var at = start
                    try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 10_000
                        c.readTimeout = 10_000
                        c.setRequestProperty("Range", "bytes=${pieceF + (at - pieceV)}-${endF - 1}")
                        c.inputStream.use { inp ->
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                got.addAndGet(n.toLong())
                                at += n
                                val now = SystemClock.elapsedRealtime()
                                val g = now - lastByte.getAndSet(now)
                                if (g > gap.get()) gap.set(g)
                                if (SystemClock.elapsedRealtime() > deadline) break
                            }
                        }
                        c.disconnect()
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        if (pieceF + (at - pieceV) < endF) {
                            retry.add(at) // the rest of this piece is fetched again
                            SystemClock.sleep(300)
                        }
                    }
                }
            }
        }
        workers.forEach { it.join() }
        val s = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val tail = SystemClock.elapsedRealtime() - lastByte.get()
        return Result(got.get() * 8 / s / 1e6, got.get() / 1e6, s, conns, errors.get(), maxOf(gap.get(), tail) / 1000.0)
    }

    /** The file's length (a HEAD request); the test cannot go on without it. */
    private fun fileLength(url: String): Long {
        repeat(3) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                val n = c.contentLengthLong
                c.disconnect()
                if (n > 0) return n
            } catch (e: Exception) {
                SystemClock.sleep(1000)
            }
        }
        error("could not get the length of $url")
    }
}
