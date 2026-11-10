package dev.netmerge.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How much faster a download gets when its connections are split over Wi-Fi and mobile data.
 *
 * Uses real data (mobile data too), so it runs only when asked: `am instrument -e speed 1 ...`.
 * Optional arguments: `-e url <file that allows ranges>`, `-e size <bytes>`, `-e conns <per network>`,
 * `-e secs <cap per run>`.
 */
@RunWith(AndroidJUnit4::class)
class SpeedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var nets: DualNetworks

    @Before
    fun setUp() {
        assumeTrue("speed test runs only with -e speed 1", args.getString("speed") != null)
        nets = DualNetworks(ctx).also { it.start() }
    }

    @After
    fun tearDown() {
        if (::nets.isInitialized) nets.stop()
    }

    @Test
    fun mergedAgainstEachNetworkAlone() {
        assertTrue(nets.report(), nets.awaitBoth(60_000))
        val url = args.getString("url") ?: "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb"
        val size = args.getString("size")?.toLong() ?: 140_000_000L // a little under the file's size
        val conns = args.getString("conns")?.toInt() ?: 8
        val secs = args.getString("secs")?.toDouble() ?: 30.0
        val wifi = nets.network(DualNetworks.Kind.WIFI)!!
        val mobile = nets.network(DualNetworks.Kind.MOBILE)!!
        val dl = SplitDownload(url, size)

        val runs = listOf(
            dl.run("Wi-Fi only, ${conns * 2} connections", List(conns * 2) { "wifi" to wifi }, secs),
            dl.run("Mobile only, ${conns * 2} connections", List(conns * 2) { "mobile" to mobile }, secs),
            dl.run("Both, $conns + $conns connections", List(conns) { "wifi" to wifi } + List(conns) { "mobile" to mobile }, secs),
        )
        val summary = runs.joinToString("\n") { it.toString() }
        Log.i(TAG, "SPEED SUMMARY\n$summary")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("summary", summary) })
        assertTrue("downloads failed:\n$summary", runs.all { it.mbps > 0.5 })
    }
}
