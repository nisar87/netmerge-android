package dev.netmerge.probe

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hours of ordinary use with merging on: every few minutes pages open and a 30 s download runs. Reports each round, so
 * slow decline (leaks, a stuck network, rising errors) shows. Holds a wake lock so it keeps running with the screen off.
 *   am instrument -w -r -e longrun 1 -e minutes 180 -e every 10 -e class dev.netmerge.probe.LongRunTest ...
 */
@RunWith(AndroidJUnit4::class)
class LongRunTest {
    private val args = InstrumentationRegistry.getArguments()

    private fun log(s: String) {
        Log.i("NetMergeLongRun", s)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("longrun", s) })
    }

    private fun page(u: String): Long {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val code = c.responseCode
            c.inputStream.use { it.readBytes() }
            if (code in 200..399) SystemClock.elapsedRealtime() - t0 else -1
        } catch (e: Exception) {
            -1
        }
    }

    @Test
    fun run() {
        assumeTrue("runs only with -e longrun 1", args.getString("longrun") != null)
        val minutes = args.getString("minutes")?.toInt() ?: 180
        val every = args.getString("every")?.toInt() ?: 10
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmerge:longrun")
        wl.acquire(minutes * 60_000L + 600_000L)
        val t0 = SystemClock.elapsedRealtime()
        var rounds = 0
        var badPages = 0
        var mbps = 0.0
        try {
            while (SystemClock.elapsedRealtime() - t0 < minutes * 60_000L) {
                val roundStart = SystemClock.elapsedRealtime()
                val pages = listOf("http://connectivitycheck.gstatic.com/generate_204", "https://www.google.com/", "https://www.wikipedia.org/").map { page(it) }
                badPages += pages.count { it < 0 }
                val r = Downloader.run(conns = 16, secs = 30.0, size = 4_000_000_000L)
                rounds++
                mbps += r.mbps
                log("minute %d round %d pages %s ms, download %.1f Mbps %.0f MB %d errors stall %.1f s, battery %d%%".format(
                    (roundStart - t0) / 60_000, rounds, pages.joinToString("/"), r.mbps, r.mb, r.errors, r.gap,
                    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)))
                val next = roundStart + every * 60_000L
                while (SystemClock.elapsedRealtime() < next && SystemClock.elapsedRealtime() - t0 < minutes * 60_000L) SystemClock.sleep(1000)
            }
            log("RESULT %d rounds, average %.1f Mbps, %d pages failed".format(rounds, if (rounds > 0) mbps / rounds else 0.0, badPages))
        } finally {
            wl.release()
        }
    }
}
