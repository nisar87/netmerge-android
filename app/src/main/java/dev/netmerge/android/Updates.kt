package dev.netmerge.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * New versions: NetMerge reads the public list of releases on GitHub (nothing about the phone or the user is sent) and,
 * when one is newer, offers it. "Download" opens the APK in the browser; Android then installs it over this one (same
 * key, settings kept). No install permission is needed in the app.
 *
 * A release says its version code in its notes, on a line "versionCode: N" (release.yml writes it).
 */
object Updates {
    private const val RELEASES = "https://api.github.com/repos/nisar87/netmerge-android/releases?per_page=10"
    const val PAGE = "https://github.com/nisar87/netmerge-android/releases"

    data class Release(val name: String, val code: Long, val apk: String, val notes: String, val beta: Boolean)

    sealed class Result {
        data class Newer(val release: Release) : Result()
        object UpToDate : Result()
        data class Failed(val why: String) : Result()
    }

    fun thisCode(ctx: Context): Long = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    }.getOrDefault(0)

    /** Asks GitHub (on a background thread: it is a network call). */
    fun check(ctx: Context): Result {
        val r = runCatching { newest() }.getOrElse { return Result.Failed(it.message ?: it.javaClass.simpleName) }
            ?: return Result.UpToDate
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("checked", System.currentTimeMillis()).apply()
        return if (r.code > thisCode(ctx)) Result.Newer(r) else Result.UpToDate
    }

    /** Checked within the last day (the screen checks by itself at most once a day). */
    fun checkedToday(ctx: Context): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("checked", 0) < 86_400_000

    private fun newest(): Release? {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val list = JSONArray(body)
        // the phone's own processor type gets its smaller APK; any other gets the one for all
        val abi = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") "arm64-v8a" else "universal"
        var best: Release? = null
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft") || !tag.startsWith("v")) continue // test-build pre-releases are not versions
            val notes = r.optString("body")
            val code = Regex("versionCode:\\s*(\\d+)").find(notes)?.groupValues?.get(1)?.toLong() ?: continue
            val assets = r.getJSONArray("assets")
            var apk: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") == "NetMerge-$tag-$abi.apk") apk = a.optString("browser_download_url")
            }
            if (apk == null) continue
            if (best == null || code > best.code) best = Release(tag.removePrefix("v"), code, apk, notes, r.optBoolean("prerelease"))
        }
        return best
    }

    /** Opens the APK in the browser, which downloads it; tapping the download installs it over this version. */
    fun download(ctx: Context, r: Release) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** What the screens show about updates (kept while the app runs). */
object UpdateState {
    val newer = androidx.compose.runtime.mutableStateOf<Updates.Release?>(null)
    val status = androidx.compose.runtime.mutableStateOf<String?>(null) // a line for Settings
    val dismissed = androidx.compose.runtime.mutableStateOf(false)      // "Later" on Home, for this run

    /** Checks and updates what the screens show; writes files/updates.json for test scripts. Background thread. */
    fun run(ctx: Context) {
        status.value = "Checking…"
        val r = Updates.check(ctx)
        when (r) {
            is Updates.Result.Newer -> { newer.value = r.release; status.value = "Version ${r.release.name} is ready: tap to download" }
            Updates.Result.UpToDate -> { newer.value = null; status.value = "You have the newest version" }
            is Updates.Result.Failed -> status.value = "Could not check: ${r.why.take(60)}"
        }
        runCatching {
            java.io.File(ctx.filesDir, "updates.json").writeText(
                org.json.JSONObject().put("result", r.javaClass.simpleName)
                    .put("newer", (r as? Updates.Result.Newer)?.release?.name ?: "")
                    .put("apk", (r as? Updates.Result.Newer)?.release?.apk ?: "")
                    .put("why", (r as? Updates.Result.Failed)?.why ?: "").toString())
        }
    }
}
