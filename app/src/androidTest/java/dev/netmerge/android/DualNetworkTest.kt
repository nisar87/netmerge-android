package dev.netmerge.android

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two questions the Android plan rests on:
 * 1. Do Wi-Fi and mobile data stay up together when an app asks for both?
 * 2. Can a connection be sent out on each one separately, also while the app runs a VPN on the phone?
 *
 * Runs on GitHub's emulator for every change, and on a real phone over USB.
 */
@RunWith(AndroidJUnit4::class)
class DualNetworkTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var nets: DualNetworks

    @Before
    fun setUp() {
        nets = DualNetworks(ctx).also { it.start() }
    }

    @After
    fun tearDown() {
        ctx.startService(Intent(ctx, SpikeVpnService::class.java).setAction(SpikeVpnService.ACTION_STOP))
        nets.stop()
    }

    @Test
    fun bothNetworksComeUpAndStayUp() {
        assertTrue("Wi-Fi and mobile data did not both come up: ${nets.report()}", nets.awaitBoth(60_000))
        SystemClock.sleep(20_000) // Android tears an unused mobile connection down after a while; the request must hold it
        assertNotNull("Wi-Fi went down", nets.network(DualNetworks.Kind.WIFI))
        assertNotNull("mobile data went down while Wi-Fi was connected", nets.network(DualNetworks.Kind.MOBILE))
        Log.i(TAG, "both up after 20 s:\n${nets.report()}")
    }

    @Test
    fun eachConnectionLeavesThroughItsOwnNetwork() {
        assertTrue(nets.report(), nets.awaitBoth(60_000))
        checkBoth("without VPN")
    }

    @Test
    fun eachConnectionStillLeavesThroughItsOwnNetworkWhileTheVpnIsUp() {
        assertTrue(nets.report(), nets.awaitBoth(60_000))
        assertNull("VPN permission was not granted for the test (appops ACTIVATE_VPN)", VpnService.prepare(ctx))
        ctx.startService(Intent(ctx, SpikeVpnService::class.java))
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!(SpikeVpnService.running && nets.vpnActive()) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(200)
        assertTrue("the VPN did not come up", SpikeVpnService.running && nets.vpnActive())
        checkBoth("with VPN")
    }

    private fun checkBoth(label: String) {
        val wifi = nets.network(DualNetworks.Kind.WIFI)!!
        val mobile = nets.network(DualNetworks.Kind.MOBILE)!!
        val pw = nets.probe(wifi)
        val pm = nets.probe(mobile)
        Log.i(TAG, "$label: wifi=$pw mobile=$pm")
        assertTrue("$label: Wi-Fi connection failed: $pw", pw.ok)
        assertTrue("$label: mobile connection failed: $pm", pm.ok)
        assertTrue("$label: the Wi-Fi connection did not leave from a Wi-Fi address: $pw", pw.onNetwork)
        assertTrue("$label: the mobile connection did not leave from a mobile address: $pm", pm.onNetwork)
        assertNotEquals("$label: both connections left from the same address", pw.local, pm.local)
        assertNotEquals(nets.linkProperties(wifi)?.interfaceName, nets.linkProperties(mobile)?.interfaceName)
        assertEquals(true, pw.ok && pm.ok)
    }
}
