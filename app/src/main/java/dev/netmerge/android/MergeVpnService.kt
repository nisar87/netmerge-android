package dev.netmerge.android

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * The merging VPN: every app's traffic comes in here and the engine sends each connection out on Wi-Fi or mobile
 * data. This app itself is left out of the VPN, so the engine's own connections go straight to the network it picks.
 */
class MergeVpnService : VpnService() {
    private var nets: DualNetworks? = null
    @Volatile private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMerging()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        stopped = false
        thread(name = "merge-start") { startMerging() }
        return START_STICKY
    }

    private fun startMerging() {
        val n = DualNetworks(this) { networksChanged() }.also { nets = it; it.start() }
        n.awaitBoth(15_000) // start with what is there; a network that comes later joins by itself
        val pfd = Builder()
            .setSession("NetMerge")
            .setMtu(MTU)
            .addAddress("10.111.0.2", 32)
            .addAddress("fd6e:6d65:7267::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
            .setUnderlyingNetworks(n.all().toTypedArray())
            .establish()
        if (pfd == null) {
            Log.e(TAG, "ENGINE the VPN could not be started (permission?)")
            return
        }
        val json = n.engineJson()
        try {
            mobile.Mobile.start(pfd.detachFd().toLong(), MTU.toLong(), json)
        } catch (e: Exception) {
            Log.e(TAG, "ENGINE failed to start: $e")
            return
        }
        running = true
        Log.i(TAG, "ENGINE started with $json")
        thread(name = "merge-stats") {
            while (!stopped) {
                SystemClock.sleep(2000)
                val s = mobile.Mobile.stats()
                if (s.isNotEmpty()) Log.i(TAG, "ENGINE $s")
            }
        }
    }

    private fun networksChanged() {
        val n = nets ?: return
        if (!running) return
        val json = n.engineJson()
        runCatching { mobile.Mobile.setNetworks(json) }
        runCatching { setUnderlyingNetworks(n.all().toTypedArray()) }
        Log.i(TAG, "ENGINE networks now $json")
    }

    private fun stopMerging() {
        stopped = true
        if (running) runCatching { mobile.Mobile.stop() }
        running = false
        nets?.stop()
        nets = null
        Log.i(TAG, "ENGINE stopped")
    }

    override fun onDestroy() {
        stopMerging()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopMerging()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "dev.netmerge.android.MERGE_STOP"
        const val MTU = 1500
        @Volatile var running = false
    }
}
