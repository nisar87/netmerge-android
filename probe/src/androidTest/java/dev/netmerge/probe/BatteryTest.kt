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

/**
 * Battery use of normal activity: every 5 minutes a download like an app update, idle in between; the battery's
 * charge counter read every minute. Run it once with merging on and once with it off and compare.
 * Holds a wake lock so the test itself keeps running with the screen off (same extra cost in both runs).
 *   am instrument -w -e battery 1 -e minutes 25 -e label merged -e class dev.netmerge.probe.BatteryTest ...
 */
@RunWith(AndroidJUnit4::class)
class BatteryTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun drain() {
        assumeTrue("runs only with -e battery 1", args.getString("battery") != null)
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val label = args.getString("label") ?: "run"
        val minutes = args.getString("minutes")?.toInt() ?: 25
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmerge:batterytest")
        wl.acquire(minutes * 60_000L + 300_000L)
        fun sample(): Long = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        fun log(s: String) {
            Log.i("NetMergeBattery", "$label $s")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("battery", "$label $s") })
        }
        try {
            val start = sample()
            val t0 = SystemClock.elapsedRealtime()
            log("start charge=${start} uAh level=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
            var downloaded = 0.0
            for (minute in 0 until minutes) {
                if (minute % 5 == 0) {
                    val r = Downloader.run(conns = 16, secs = 40.0)
                    downloaded += r.mb
                    log("minute $minute download %.1f Mbps %.0f MB %d errors".format(r.mbps, r.mb, r.errors))
                }
                val next = t0 + (minute + 1) * 60_000L
                while (SystemClock.elapsedRealtime() < next) SystemClock.sleep(1000)
                log("minute ${minute + 1} charge=${sample()} uAh")
            }
            val used = (start - sample()) / 1000.0
            log("RESULT used %.0f mAh in %d minutes (%.0f mAh per hour), downloaded %.0f MB".format(used, minutes, used * 60 / minutes, downloaded))
        } finally {
            wl.release()
        }
    }
}
