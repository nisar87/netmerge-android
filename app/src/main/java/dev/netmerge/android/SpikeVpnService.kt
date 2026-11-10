package dev.netmerge.android

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The smallest possible "VPN on the phone": it only proves that while a VPN is up, this app can still send its own
 * connections out on Wi-Fi and on mobile data separately. The real app will hand the VPN's traffic to the engine,
 * which then does exactly that for every app.
 *
 * Traffic of other apps is routed into the VPN and dropped here, so this service is only for tests.
 */
class SpikeVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) {
            tun = Builder()
                .setSession("NetMerge test")
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)
                // this app's own connections must not loop back into its VPN: it picks Wi-Fi or mobile data itself
                .addDisallowedApplication(packageName)
                .setBlocking(false)
                .establish()
            Log.i(TAG, "VPN established: ${tun != null}")
            running = tun != null
        }
        return START_STICKY
    }

    private fun stopVpn() {
        runCatching { tun?.close() }
        tun = null
        running = false
        Log.i(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "dev.netmerge.android.STOP"
        @Volatile var running = false
    }
}
