package dev.netmerge.android

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mobile data that merging used, per day (kept on the phone). Only what went over mobile data while Wi-Fi was also
 * there counts: without Wi-Fi the phone would have used mobile data anyway.
 */
object Usage {
    private const val PREFS = "usage"
    private var pending = 0L
    private var lastFlush = 0L

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    fun add(ctx: Context, bytes: Long) {
        if (bytes <= 0) return
        pending += bytes
        val now = System.currentTimeMillis()
        if (now - lastFlush > 20_000) flush(ctx)
    }

    @Synchronized
    fun flush(ctx: Context) {
        lastFlush = System.currentTimeMillis()
        if (pending == 0L) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "mobile-" + today()
        p.edit().putLong(key, p.getLong(key, 0) + pending).apply()
        pending = 0
    }

    /** Ads and trackers blocked today (kept on the phone). */
    @Synchronized
    fun addBlocked(ctx: Context, n: Long) {
        if (n <= 0) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "blocked-" + today()
        p.edit().putLong(key, p.getLong(key, 0) + n).apply()
    }

    fun blockedToday(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("blocked-" + today(), 0)

    /** Bytes of mobile data merging used today. */
    @Synchronized
    fun mobileToday(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("mobile-" + today(), 0) + pending
}

/** The user's settings. */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun onDemand(ctx: Context) = p(ctx).getBoolean("mobileOnDemand", false)
    fun setOnDemand(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("mobileOnDemand", v).apply(); EventLog.add(ctx, "setting: mobile data only when it helps = $v") }
    /** Mobile data may be used for merging while roaming (off: roaming abroad can cost a lot). */
    fun roaming(ctx: Context) = p(ctx).getBoolean("mobileRoaming", false)
    fun setRoaming(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("mobileRoaming", v).apply(); EventLog.add(ctx, "setting: mobile data while roaming = $v") }
    /** Daily limit for mobile data used by merging, in MB; 0 = no limit. */
    fun limitMB(ctx: Context) = p(ctx).getInt("mobileLimitMB", 0)
    fun setLimitMB(ctx: Context, v: Int) { p(ctx).edit().putInt("mobileLimitMB", v).apply(); EventLog.add(ctx, "setting: daily limit for mobile data = $v MB") }
    val limits = intArrayOf(0, 500, 1000, 2000, 5000)
    /** Merging pauses when the battery falls to this level (%); 0 = never. */
    fun batteryStop(ctx: Context) = p(ctx).getInt("batteryStop", 0)
    fun setBatteryStop(ctx: Context, v: Int) { p(ctx).edit().putInt("batteryStop", v).apply(); EventLog.add(ctx, "setting: pause at battery $v%") }
    /** A pause for the battery ends when the battery has charged back to this level (%). */
    fun batteryResume(ctx: Context) = p(ctx).getInt("batteryResume", 50)
    fun setBatteryResume(ctx: Context, v: Int) { p(ctx).edit().putInt("batteryResume", v).apply(); EventLog.add(ctx, "setting: carry on at battery $v%") }
    val batteryStops = intArrayOf(0, 10, 15, 20, 25, 30, 40)
    val batteryResumes = intArrayOf(30, 40, 50, 60, 80, 90)
}
/** The apps left out of merging (package names). */
object Excluded {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(c: Context): Set<String> = p(c).getStringSet("excludedApps", emptySet()) ?: emptySet()
    fun set(c: Context, pkg: String, on: Boolean) {
        val now = get(c).toMutableSet()
        if (on) now += pkg else now -= pkg
        p(c).edit().putStringSet("excludedApps", now).apply()
        EventLog.add(c, "setting: an app ${if (on) "left out of" else "back in"} merging (${now.size} left out)") // not which: privacy
    }
}
