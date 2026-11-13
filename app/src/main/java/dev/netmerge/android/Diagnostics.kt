package dev.netmerge.android

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Diagnostics: on by default, a switch in Settings (and on the welcome screen) turns it off.
 *
 * What is sent, a few times a day and on Wi-Fi: NetMerge's own event log (starts, stops, network changes, what each
 * network carried; never which sites or apps you use, nor any addresses), the app's settings, the phone's make, model
 * and Android version, the app's version and a random number made on this phone that tells reports from one phone
 * apart. After a crash, the crash's details, at the next start on any network.
 *
 * Where: the project's own database (Firebase project netmerge-app), which the app may only add to: it cannot read
 * anything back, not even its own reports (diagnostics/firestore.rules).
 */
object Diagnostics {
    private const val KEY = "AIzaSyCePls0QESUovZDjLV1D22YtE_Lgua18ek" // the project's web key: it only reaches the rules above
    private const val URL_REPORTS =
        "https://firestore.googleapis.com/v1/projects/netmerge-app/databases/(default)/documents/reports?key=$KEY"
    private const val EVERY_MS = 6 * 3_600_000L // at most a log report every 6 hours
    private const val MAX_LOG = 120_000          // characters of log in one report (the rules allow 200 000)
    private const val STATS_EVERY_MS = 60_000L   // the 2 s traffic lines, thinned to one a minute

    private fun p(c: Context) = c.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, v: Boolean) {
        p(c).edit().putBoolean("on", v).apply()
        EventLog.add(c, "setting: send diagnostics = $v")
        if (!v) runCatching { crashFile(c).delete() } // nothing waits to go out once it is off
    }

    /** When the last report went out (0: never). */
    fun lastSent(c: Context) = p(c).getLong("sent", 0)

    /** A random number for this installation (not tied to the phone or the person); new after a reinstall. */
    fun installId(c: Context): String = p(c).getString("install", null) ?: UUID.randomUUID().toString().also {
        p(c).edit().putString("install", it).apply()
    }

    private fun crashFile(c: Context) = java.io.File(c.filesDir, "crash.txt")

    @Volatile private var ready = false

    /** Keeps a crash's details for the next start. Call once per process start; later calls do nothing. */
    fun init(ctx: Context) {
        if (ready) return
        ready = true
        val app = ctx.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                if (enabled(app)) {
                    val w = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(w))
                    crashFile(app).writeText("${System.currentTimeMillis()} thread ${t.name}\n${w.toString().take(45_000)}")
                }
                EventLog.error(app, "CRASH in thread ${t.name}", e)
            }
            before?.uncaughtException(t, e)
        }
        // a crash in the engine (Go) ends the app without a Java stack: the engine writes it to this file
        val engineCrash = java.io.File(app.filesDir, "engine-crash.txt")
        runCatching {
            if (engineCrash.length() > 0) {
                val text = engineCrash.readText().take(45_000)
                EventLog.add(app, "CRASH in the engine (details in the crash report): ${text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200)}")
                if (enabled(app)) crashFile(app).appendText("${System.currentTimeMillis()} engine (Go)\n$text\n")
                engineCrash.delete()
            }
        }
        app.logged("set the engine's crash file") { mobile.Mobile.setCrashFile(engineCrash.path) }
        thread(name = "exit-reasons") {
            EventLog.add(app, "app: process up, version ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()} " +
                "(code ${Updates.thisCode(app)}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            app.logged("read why the app ended last time") { exitReasons(app) }
            maybeSend(app) // a crash or freeze found just now goes out at once
        }
    }

    /**
     * Why the app's process ended before (Android 11 and later keeps this): killed for memory, an "app not
     * responding", a crash in native code (the engine), stopped by the user or by the system. Each new one is logged;
     * crashes and freezes also go out as a crash report, with Android's own trace when it has one.
     */
    private fun exitReasons(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = c.getSystemService(android.app.ActivityManager::class.java)
        val seen = p(c).getLong("exitSeen", 0)
        val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 10).filter { it.timestamp > seen }.sortedBy { it.timestamp }
        for (x in list) {
            val why = when (x.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native: the engine or Android)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: phone low on memory"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "ended itself"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user (force stop or similar)"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on died"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: too much battery or processor"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "could not start"
                android.app.ApplicationExitInfo.REASON_OTHER -> "other (system)"
                15 -> "an app setting changed (REASON_PACKAGE_STATE_CHANGE)"
                16 -> "the app was updated or reinstalled"
                else -> "reason ${x.reason}"
            }
            EventLog.add(c, "app: last run ended at ${iso(x.timestamp)}: $why; ${x.description ?: ""}; importance ${x.importance}, " +
                "memory ${x.pss / 1024} MB")
            val bad = x.reason == android.app.ApplicationExitInfo.REASON_ANR || x.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
            if (bad && enabled(c)) {
                val trace = runCatching { x.traceInputStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                crashFile(c).appendText("${x.timestamp} $why: ${x.description ?: ""}\n${trace?.take(40_000) ?: "(no trace)"}\n")
            }
        }
        list.lastOrNull()?.let { p(c).edit().putLong("exitSeen", it.timestamp).apply() }
    }

    @Volatile private var sending = false

    /** Sends a report when one is due (a crash waits, or 6 hours passed and there is Wi-Fi). Returns at once. */
    fun maybeSend(ctx: Context, force: Boolean = false) {
        val c = ctx.applicationContext
        if (!enabled(c) || sending) return
        val crash = crashFile(c).takeIf { it.exists() }
        val due = force || System.currentTimeMillis() - lastSent(c) >= EVERY_MS
        if (crash == null && !due) return
        if (crash == null && !force && !unmetered(c)) return
        sending = true
        thread(name = "diagnostics") {
            try {
                send(c, crash)
            } finally {
                sending = false
            }
        }
    }

    private fun unmetered(c: Context): Boolean = runCatching {
        !c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    private fun send(c: Context, crash: java.io.File?) {
        val since = p(c).getLong("logUpTo", 0)
        val (log, upTo) = logSince(c, since)
        val f = JSONObject()
        fun s(k: String, v: String) = f.put(k, JSONObject().put("stringValue", v))
        s("install", installId(c))
        s("kind", if (crash != null) "crash" else "log")
        s("app", runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?")
        s("code", Updates.thisCode(c).toString())
        s("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
        s("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        s("settings", settings(c).take(4000))
        s("engine", runCatching { java.io.File(c.filesDir, "engine.json").readText() }.getOrDefault("").take(8000))
        s("log", log)
        if (crash != null) s("crash", runCatching { crash.readText() }.getOrDefault("").take(50_000))
        f.put("at", JSONObject().put("timestampValue", iso(System.currentTimeMillis())))
        val ok = runCatching {
            val h = URL(URL_REPORTS).openConnection() as HttpURLConnection
            h.connectTimeout = 15_000
            h.readTimeout = 20_000
            h.requestMethod = "POST"
            h.doOutput = true
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            h.outputStream.use { it.write(JSONObject().put("fields", f).toString().toByteArray()) }
            val code = h.responseCode
            h.disconnect()
            code
        }.getOrElse { -1 }
        note(c, "sent=$ok kind=${if (crash != null) "crash" else "log"} chars=${log.length}")
        if (ok in 200..299) {
            crash?.delete()
            p(c).edit().putLong("sent", System.currentTimeMillis()).putLong("logUpTo", upTo).apply()
        }
    }

    /** The event log's lines after `since`, the 2 s traffic lines thinned, newest kept if it is too long. */
    private fun logSince(c: Context, since: Long): Pair<String, Long> {
        val lines = listOf(EventLog.older(c), EventLog.file(c)).flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) }
        val out = ArrayList<String>()
        var lastStats = 0L
        var upTo = since
        for (l in lines) {
            val t = l.substringBefore(' ').toLongOrNull() ?: continue
            if (t <= since) continue
            upTo = maxOf(upTo, t)
            if (l.startsWith("$t stats ")) {
                if (t - lastStats < STATS_EVERY_MS) continue
                lastStats = t
            }
            out += "${iso(t)} ${l.substringAfter(' ')}"
        }
        var text = out.joinToString("\n")
        if (text.length > MAX_LOG) text = "(older lines left out)\n" + text.takeLast(MAX_LOG)
        return text to upTo
    }

    private fun settings(c: Context): String {
        val o = JSONObject()
        for ((k, v) in c.getSharedPreferences("settings", Context.MODE_PRIVATE).all) {
            if (v is Set<*>) o.put(k, v.size) // apps left out: how many, not which
            else o.put(k, v.toString().take(200))
        }
        return o.toString()
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** For tests: files/diagnostics.log says what was sent and the answer. */
    private fun note(c: Context, what: String) {
        runCatching {
            val f = java.io.File(c.filesDir, "diagnostics.log")
            if (f.length() > 20_000) f.delete()
            f.appendText("${System.currentTimeMillis()} $what\n")
        }
    }
}
