package dev.netmerge.android

import android.content.Context
import android.util.Log
import java.io.File

/**
 * NetMerge's own log: files/events.log, one line per event ("<ms since 1970> <what>"). The phone's log keeps only a
 * little (seen on a Galaxy S26 Ultra: lines were gone within seconds), so everything worth knowing after a problem
 * goes here: test scripts read it over adb, and diagnostics sends it (Diagnostics.kt).
 *
 * Kept to about 400 KB: at 200 KB the file becomes events.1.log (the one before is dropped) and a new one begins.
 * Never written: sites, addresses or which apps are used (the diagnostics promise).
 *
 * Words the test scripts look for ("started", "stopped", "resumed", "mobile held", "mobile released") are written only
 * by the merging service for those events; other lines must not use them.
 */
object EventLog {
    private const val MAX = 200_000L
    @Volatile private var dir: File? = null

    fun file(c: Context) = File(c.filesDir, "events.log")
    fun older(c: Context) = File(c.filesDir, "events.1.log")

    /** Adds a line. Any thread; never throws. */
    fun add(c: Context, what: String) {
        Log.i(TAG, what)
        runCatching {
            val d = dir ?: c.applicationContext.filesDir.also { dir = it }
            synchronized(this) {
                val f = File(d, "events.log")
                if (f.length() > MAX) f.renameTo(File(d, "events.1.log"))
                f.appendText("${System.currentTimeMillis()} ${what.replace('\n', ' ')}\n")
            }
        }
    }

    /** A failure with its cause: the exception's type, message and where it happened (the first lines of NetMerge's
     *  own code in the stack). */
    fun error(c: Context, what: String, e: Throwable) {
        val at = e.stackTrace.filter { it.className.startsWith("dev.netmerge") }.take(3)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val cause = generateSequence(e.cause) { it.cause }.take(2).joinToString("") { " / caused by ${it.javaClass.simpleName}: ${it.message}" }
        add(c, "ERROR $what: ${e.javaClass.simpleName}: ${e.message?.take(300)}$cause${if (at.isNotEmpty()) " at $at" else ""}")
    }
}

/** Runs block; a failure is written to the log (with what was being done) instead of disappearing. */
inline fun <T> Context.logged(what: String, block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    if (e is VirtualMachineError) throw e
    EventLog.error(this, what, e)
    null
}
