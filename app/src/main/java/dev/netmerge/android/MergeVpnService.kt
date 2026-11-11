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
    @Volatile private var active = false // starting or running
    @Volatile private var generation = 0 // each start gets its own number; threads of an older start stop

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMerging()
            stopSelf(startId) // not stopSelf(): a start sent right after this stop must still run
            return START_NOT_STICKY
        }
        if (active) return START_STICKY // already starting or running
        active = true
        stopped = false
        mtu = intent?.getIntExtra("mtu", DEFAULT_MTU)?.takeIf { it in 1280..65535 } ?: DEFAULT_MTU
        if (intent?.hasExtra("ondemand") == true) {
            Settings.setOnDemand(this, intent.getBooleanExtra("ondemand", false))
        }
        if (intent?.hasExtra("limitmb") == true) Settings.setLimitMB(this, intent.getIntExtra("limitmb", 0))
        if (intent?.hasExtra("blocking") == true) Blocklists.setOn(this, intent.getBooleanExtra("blocking", true))
        intent?.getStringExtra("exclude")?.let { Excluded.set(this, it, true) } // tests
        intent?.getStringExtra("include")?.let { Excluded.set(this, it, false) }
        goForeground()
        val gen = ++generation
        thread(name = "merge-start") { safeStart(gen) }
        return START_STICKY
    }

    /** A merging VPN must run in the foreground (with its notification), or the phone throttles it when the
     *  screen is off. Seen on a Galaxy S26 Ultra: a merged download dropped from about 85 to 26 Mbps. */
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("merge", "Merging", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val stop = android.app.PendingIntent.getService(this, 1, Intent(this, MergeVpnService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "merge")
            .setContentTitle("NetMerge is merging Wi-Fi and mobile data")
            .setContentText("Tap to see what each network carries")
            .setSmallIcon(R.drawable.ic_merge)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(1, n)
        } catch (e: Exception) {
            Log.e(TAG, "ENGINE could not run in the foreground: $e")
        }
    }

    private fun safeStart(gen: Int) {
        try {
            startMerging(gen)
        } catch (e: Exception) {
            Log.e(TAG, "ENGINE start failed", e)
            event("start failed: $e")
            failed(gen)
        }
    }

    private fun startMerging(gen: Int) {
        val n = DualNetworks(this) { networksChanged() }.also { nets = it; it.start() }
        n.awaitBoth(15_000) // start with what is there; a network that comes later joins by itself
        if (stopped || gen != generation) return // stopped while waiting for the networks
        runCatching { Blocklists.apply(this) } // the lists already on the phone; fresh ones are fetched below
        val pfd = Builder()
            .setSession("NetMerge")
            .setMtu(mtu)
            .addAddress("10.111.0.2", 32)
            .addAddress("fd6e:6d65:7267::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // the engine's own DNS address: blocked names are answered there, the rest go to a real DNS server
            .addDnsServer("10.111.0.53")
            .addDnsServer("fd6e:6d65:7267::53")
            .addDisallowedApplication(packageName)
            .apply {
                // apps the user left out of merging use the phone's normal network
                for (pkg in Excluded.get(this@MergeVpnService)) runCatching { addDisallowedApplication(pkg) }
            }
            .setUnderlyingNetworks(n.all().toTypedArray())
            .establish()
        if (pfd == null) {
            Log.e(TAG, "ENGINE the VPN could not be started (permission?)")
            event("start failed: no VPN (permission?)")
            failed(gen)
            return
        }
        val json = n.engineJson()
        val fd = pfd.detachFd().toLong()
        val err = runCatching { mobile.Mobile.start(fd, mtu.toLong(), json) }.exceptionOrNull()
        if (err != null) {
            // "already running": an engine of an earlier start was still there; stop it and try once more
            runCatching { mobile.Mobile.stop() }
            val again = runCatching { mobile.Mobile.start(fd, mtu.toLong(), json) }.exceptionOrNull()
            event("start: engine said ${err.message}; ${if (again == null) "started on the second try" else "second try failed: ${again.message}"}")
            if (again != null) {
                Log.e(TAG, "ENGINE failed to start: $again")
                runCatching { android.os.ParcelFileDescriptor.adoptFd(fd.toInt()).close() }
                failed(gen)
                return
            }
        }
        if (stopped || gen != generation) {
            // stopped while starting: do not leave this engine running
            runCatching { mobile.Mobile.stop() }
            return
        }
        running = true
        retried = false
        thread(name = "blocklists") {
            if (runCatching { Blocklists.refresh(this) }.getOrDefault(false)) runCatching { Blocklists.apply(this) }
        }
        MergeTileService.update(this)
        Log.i(TAG, "ENGINE started with $json")
        event("started mtu=$mtu $json")
        thread(name = "merge-stats") {
            var lastRx = -1L
            var lastMobile = 0L
            // a stop followed at once by a new start must end this loop: it belongs to the old networks
            while (!stopped && gen == generation) {
                SystemClock.sleep(2000)
                val s = mobile.Mobile.stats()
                if (s.isEmpty()) continue
                Log.i(TAG, "ENGINE $s")
                runCatching { java.io.File(filesDir, "engine.json").writeText(s) }
                event("stats $s") // a timeline for tests: what each network carried, every 2 s
                val nets = runCatching { org.json.JSONObject(s).getJSONArray("nets") }.getOrNull() ?: continue
                val all = (0 until nets.length()).map { nets.getJSONObject(it) }
                val rx = all.sumOf { it.getLong("rx") }
                val rate = if (lastRx in 0..rx) (rx - lastRx) / 2.0 else 0.0
                lastRx = rx
                // mobile data used by merging (counted only while Wi-Fi is there too)
                val m = all.firstOrNull { it.getString("name") == "mobile" }
                val mobileNow = if (m == null) 0L else m.getLong("rx") + m.getLong("tx")
                val used = if (mobileNow >= lastMobile) mobileNow - lastMobile else mobileNow // counters restart when mobile rejoins
                lastMobile = mobileNow
                if (n.usable(DualNetworks.Kind.WIFI)) Usage.add(this, used)
                mobilePolicy(rate, n)
            }
        }
    }

    private var busyFor = 0
    private var quietFor = 0

    /**
     * Whether to keep mobile data up, decided every 2 s:
     * - without Wi-Fi: always (it is the only way out);
     * - when merging used up the daily limit for mobile data: no;
     * - "only when it helps" on: up while there is a lot to download, let go after a quiet minute (saves battery);
     * - otherwise: always.
     */
    private fun mobilePolicy(bytesPerSecond: Double, n: DualNetworks) {
        val wifi = n.usable(DualNetworks.Kind.WIFI)
        val held = n.held(DualNetworks.Kind.MOBILE)
        val limit = Settings.limitMB(this).toLong() * 1_000_000
        overLimit = limit > 0 && Usage.mobileToday(this) >= limit
        onDemand = Settings.onDemand(this)
        if (bytesPerSecond > BUSY) { busyFor++; quietFor = 0 } else if (bytesPerSecond < QUIET) { quietFor++; busyFor = 0 } else { busyFor = 0; quietFor = 0 }
        val (want, why) = when {
            !wifi -> true to "no Wi-Fi"
            overLimit -> false to "daily limit reached"
            !onDemand -> true to "always"
            held -> (quietFor < 30) to "quiet for a minute"
            else -> (busyFor >= 2) to "busy %.0f KB/s".format(bytesPerSecond / 1024)
        }
        if (want != held) {
            n.hold(DualNetworks.Kind.MOBILE, want)
            if (!want) quietFor = 0
            event((if (want) "mobile held: " else "mobile released: ") + why)
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

    /** Starting failed: start again once after 2 s (the earlier VPN may still have been closing), then give up. */
    private var retried = false

    private fun failed(gen: Int) {
        nets?.stop()
        nets = null
        if (retried || stopped || gen != generation) {
            active = false
            return
        }
        retried = true
        thread(name = "merge-retry") {
            SystemClock.sleep(2000)
            if (!stopped && gen == generation) {
                event("start: trying again")
                safeStart(gen)
            }
        }
    }

    private fun stopMerging() {
        Usage.flush(this)
        generation++
        stopped = true
        active = false
        runCatching { mobile.Mobile.stop() } // always: an engine of a start that had not finished must not be left running
        running = false
        MergeTileService.update(this)
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
        /** The VPN's packet size. Bigger packets mean fewer of them for the engine's stack to handle per GB (the
         *  networks themselves still use their own size). Tests can pass "mtu" to compare. */
        const val DEFAULT_MTU = 9000 // phone: about 44 s of processor per GB against 63 s at 1500, same speed
        @Volatile var mtu = DEFAULT_MTU
        /** Mobile data only while there is a lot to download (see mobileOnDemand). Off by default: connections an
         *  app already opened on Wi-Fi stay there (apps reuse them), so a download that started while mobile data
         *  was let go gains less. Worth it only if keeping mobile data up costs real battery (being measured). */
        @Volatile var onDemand = false
        /** Merging used up today's limit for mobile data (shown on the screen). */
        @Volatile var overLimit = false
        private const val BUSY = 1_000_000.0 // bytes per second for 4 s: merging can help, bring mobile data up
        private const val QUIET = 100_000.0 // bytes per second for a minute: nothing to gain, let mobile data go
        @Volatile var running = false
    }
}
