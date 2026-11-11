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

    /** Bytes of mobile data merging used today. */
    @Synchronized
    fun mobileToday(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("mobile-" + today(), 0) + pending
}

/** The user's settings. */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun onDemand(ctx: Context) = p(ctx).getBoolean("mobileOnDemand", false)
    fun setOnDemand(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("mobileOnDemand", v).apply()
    /** Daily limit for mobile data used by merging, in MB; 0 = no limit. */
    fun limitMB(ctx: Context) = p(ctx).getInt("mobileLimitMB", 0)
    fun setLimitMB(ctx: Context, v: Int) = p(ctx).edit().putInt("mobileLimitMB", v).apply()
    val limits = intArrayOf(0, 500, 1000, 2000, 5000)
}
