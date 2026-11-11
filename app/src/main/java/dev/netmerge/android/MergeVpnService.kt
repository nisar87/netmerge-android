package dev.netmerge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
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
        goForeground()
        thread(name = "merge-start") { startMerging() }
        return START_STICKY
    }

    /** A merging VPN must run in the foreground (with its notification), or the phone throttles it when the
     *  screen is off. Seen on a Galaxy S26 Ultra: a merged download dropped from about 85 to 26 Mbps. */
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("merge", "Merging", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "merge")
            .setContentTitle("NetMerge is merging Wi-Fi and mobile data")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(1, n)
        } catch (e: Exception) {
            Log.e(TAG, "ENGINE could not run in the foreground: $e")
        }
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
        event("started $json")
        thread(name = "merge-stats") {
            while (!stopped) {
                SystemClock.sleep(2000)
                val s = mobile.Mobile.stats()
                if (s.isNotEmpty()) {
                    Log.i(TAG, "ENGINE $s")
                    runCatching { java.io.File(filesDir, "engine.json").writeText(s) }
                    event("stats $s") // a timeline for tests: what each network carried, every 2 s
                }
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
        event("networks $json")
    }

    private fun stopMerging() {
        stopped = true
        if (running) runCatching { mobile.Mobile.stop() }
        running = false
        nets?.stop()
        nets = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Log.i(TAG, "ENGINE stopped")
        event("stopped")
    }

    /** Events also go to a file: the phone's log keeps only a little (seen on a Galaxy S26 Ultra: lines were gone
     *  within seconds), so tests read files/events.log and files/engine.json with "adb shell run-as". */
    private fun event(what: String) {
        runCatching {
            val f = java.io.File(filesDir, "events.log")
            if (f.length() > 200_000) f.delete()
            f.appendText("${System.currentTimeMillis()} $what\n")
        }
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
