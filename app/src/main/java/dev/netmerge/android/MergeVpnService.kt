package dev.netmerge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
        if (intent?.action == ACTION_CHECK) { // a setting changed on the screen
            if (active) checkBattery() else stopSelf(startId)
            return if (active) START_STICKY else START_NOT_STICKY
        }
        // settings that came with the start (tests): taken also when merging already runs
        if (intent?.hasExtra("ondemand") == true) {
            Settings.setOnDemand(this, intent.getBooleanExtra("ondemand", false))
        }
        if (intent?.hasExtra("limitmb") == true) Settings.setLimitMB(this, intent.getIntExtra("limitmb", 0))
        if (intent?.hasExtra("blocking") == true) {
            Blocklists.setOn(this, intent.getBooleanExtra("blocking", true))
            if (active) thread { runCatching { Blocklists.apply(this) } }
        }
        intent?.getStringExtra("exclude")?.let { Excluded.set(this, it, true) }
        intent?.getStringExtra("include")?.let { Excluded.set(this, it, false) }
        if (intent?.hasExtra("batterystop") == true) Settings.setBatteryStop(this, intent.getIntExtra("batterystop", 0))
        if (intent?.hasExtra("batteryresume") == true) Settings.setBatteryResume(this, intent.getIntExtra("batteryresume", 50))
        if (active) { checkBattery(); return START_STICKY } // already starting, running or paused
        active = true
        stopped = false
        batteryPaused = false
        armed = null
        mtu = intent?.getIntExtra("mtu", DEFAULT_MTU)?.takeIf { it in 1280..65535 } ?: DEFAULT_MTU
        goForeground()
        val gen = ++generation
        thread(name = "merge-start") { safeStart(gen) }
        watchBattery()
        return START_STICKY
    }

    private var batteryReceiver: BroadcastReceiver? = null
    /** Whether a fall to the stop level pauses merging. Not while merging was started with the battery already at
     *  or below it (the user asked for it then); it arms once the battery is above the level again. */
    private var armed: Boolean? = null

    /** Android tells us of every change of the battery level (and at once when we register). */
    private fun watchBattery() {
        if (batteryReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = batteryChanged(i)
        }
        batteryReceiver = r
        runCatching { registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    }

    private fun unwatchBattery() {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
    }

    private fun checkBattery() {
        runCatching { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()?.let { batteryChanged(it) }
    }

    /**
     * Battery guard: merging pauses when the battery falls to the stop level and starts again once it has charged
     * back to the resume level. While paused the VPN is closed (apps use the phone's normal network) but this
     * service stays, with its notification, to watch the battery.
     */
    @Synchronized
    private fun batteryChanged(i: Intent) {
        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (raw < 0 || !active) return
        val level = raw * 100 / scale
        batteryLevel = level
        val stopAt = Settings.batteryStop(this)
        val resumeAt = resumeLevel(this)
        if (batteryPaused) {
            if (stopAt == 0 || level >= resumeAt) resumeAfterBattery(level)
            else showStatus("Battery $level%. Merging starts again at $resumeAt%.")
            return
        }
        if (stopAt == 0) { armed = null; return }
        if (armed == null) armed = level > stopAt
        if (level > stopAt) armed = true
        else if (armed == true) pauseForBattery(level, resumeAt)
    }

    private fun pauseForBattery(level: Int, resumeAt: Int) {
        batteryPaused = true
        Usage.flush(this)
        generation++ // ends the stats thread and any start still under way
        runCatching { mobile.Mobile.stop() }
        running = false
        nets?.stop()
        nets = null
        MergeTileService.update(this)
        event("paused: battery $level%, starts again at $resumeAt%")
        showStatus("Battery $level%. Merging starts again at $resumeAt%.")
    }

    private fun resumeAfterBattery(level: Int) {
        batteryPaused = false
        armed = true
        retried = false
        event("resumed: battery $level%")
        showStatus("Starting…")
        val gen = ++generation
        thread(name = "merge-start") { safeStart(gen) }
    }

    /** The notification while merging: what it does right now, a tap opens the app, Stop stops. */
    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("merge", "Merging", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val stop = android.app.PendingIntent.getService(this, 1, Intent(this, MergeVpnService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "merge")
            .setContentTitle(if (batteryPaused) "NetMerge paused to save battery" else "NetMerge is merging")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_merge)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private var shown = ""

    /** Updates the notification's text when it changed (at most every few seconds; costs nothing otherwise). */
    private fun showStatus(text: String) {
        if (text == shown) return
        shown = text
        runCatching { getSystemService(NotificationManager::class.java).notify(1, notification(text)) }
    }

    /** A merging VPN must run in the foreground (with its notification), or the phone throttles it when the
     *  screen is off. Seen on a Galaxy S26 Ultra: a merged download dropped from about 85 to 26 Mbps. */
    private fun goForeground() {
        val n = notification("Starting…")
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
        problem = null
        thread(name = "blocklists") {
            if (runCatching { Blocklists.refresh(this) }.getOrDefault(false)) runCatching { Blocklists.apply(this) }
        }
        MergeTileService.update(this)
        Log.i(TAG, "ENGINE started with $json")
        event("started mtu=$mtu $json")
        thread(name = "merge-stats") {
            var lastRx = -1L
            var lastAt = SystemClock.elapsedRealtime()
            var lastMobile = 0L
            var lastBlocked = 0L
            var tick = 0
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
                // bytes per second over the time that really passed: the 2 s sleep can last much longer (phone
                // asleep, busy), and dividing by 2 then showed false speed spikes in the notification
                val now = SystemClock.elapsedRealtime()
                val secs = ((now - lastAt) / 1000.0).coerceAtLeast(0.5)
                lastAt = now
                val rate = if (lastRx in 0..rx) (rx - lastRx) / secs else 0.0
                lastRx = rx
                // mobile data used by merging (counted only while Wi-Fi is there too)
                val m = all.firstOrNull { it.getString("name") == "mobile" }
                val mobileNow = if (m == null) 0L else m.getLong("rx") + m.getLong("tx")
                val used = if (mobileNow >= lastMobile) mobileNow - lastMobile else mobileNow // counters restart when mobile rejoins
                lastMobile = mobileNow
                if (n.usable(DualNetworks.Kind.WIFI)) Usage.add(this, used)
                mobilePolicy(rate, n)
                val blocked = runCatching { org.json.JSONObject(mobile.Mobile.blockStats()).getLong("blocked") }.getOrDefault(lastBlocked)
                Usage.addBlocked(this, if (blocked >= lastBlocked) blocked - lastBlocked else blocked)
                lastBlocked = blocked
                if (tick++ % 2 == 0) {
                    val total = all.sumOf { it.getLong("rx") + it.getLong("tx") }.toDouble()
                    val parts = all.filter { total > 0 }.joinToString(" · ") {
                        (if (it.getString("name") == "wifi") "Wi-Fi " else "Mobile ") + "${((it.getLong("rx") + it.getLong("tx")) * 100 / total).toInt()}%"
                    }
                    val mbps = rate * 8 / 1e6
                    showStatus(listOfNotNull(
                        if (mbps >= 0.1) "%.1f Mbps".format(mbps) else "Idle",
                        parts.ifEmpty { null },
                        if (Blocklists.on(this)) "${Usage.blockedToday(this)} ads blocked today" else null,
                    ).joinToString(" · "))
                }
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
        if (gen != generation) return // an older start: whoever ended it (stop, pause) already cleaned up
        nets?.stop()
        nets = null
        if (retried || stopped || gen != generation) {
            active = false
            unwatchBattery()
            if (!stopped) problem = "NetMerge could not start. Check that Wi-Fi or mobile data is on, then try again."
            stopForeground(STOP_FOREGROUND_REMOVE)
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
        batteryPaused = false
        unwatchBattery()
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
        problem = "Another VPN app took over, so NetMerge stopped."
        stopMerging()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "dev.netmerge.android.MERGE_STOP"
        /** Look at the battery again now (a battery setting changed). */
        const val ACTION_CHECK = "dev.netmerge.android.MERGE_CHECK"
        /** Merging is paused until the battery charges back (see batteryChanged). */
        @Volatile var batteryPaused = false
        @Volatile var batteryLevel = -1
        /** The resume level, at least 5 points above the stop level so it cannot flip back and forth. */
        fun resumeLevel(c: Context) = maxOf(Settings.batteryResume(c), Settings.batteryStop(c) + 5)
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
        /** Why merging stopped by itself, for the screen (null: nothing to say). */
        @Volatile var problem: String? = null
    }
}
