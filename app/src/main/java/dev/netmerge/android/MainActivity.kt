package dev.netmerge.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The app's screen: one button to start or stop merging, and per network what it carries right now.
 * Tests start and stop merging with: adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true|false
 */
class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var total: TextView
    private lateinit var toggle: Button
    private lateinit var rows: Map<String, Row>
    private lateinit var diag: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val last = HashMap<String, Pair<Long, Long>>() // network -> (rx bytes, time ms)
    private var pendingMerge = false

    private class Row(val box: LinearLayout, val title: TextView, val detail: TextView, val bar: View, val barBack: LinearLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) Color.rgb(236, 238, 241) else Color.rgb(24, 28, 33)
        val muted = if (dark) Color.rgb(150, 156, 165) else Color.rgb(96, 102, 112)
        val card = if (dark) Color.rgb(32, 36, 42) else Color.rgb(242, 244, 247)
        val bg = if (dark) Color.rgb(18, 20, 24) else Color.WHITE

        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        state = text(16f, muted)
        total = text(40f, fg, bold = true).apply { text = "–" }
        toggle = Button(this).apply {
            textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { if (MergeVpnService.running) stopMerge() else startMerge() }
        }
        fun row(name: String): Row {
            val title = text(17f, fg, bold = true).apply { this.text = name }
            val detail = text(14f, muted)
            val bar = View(this)
            val back = LinearLayout(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (dark) Color.rgb(52, 57, 64) else Color.rgb(222, 226, 231)) }
                addView(bar, LinearLayout.LayoutParams(0, dp(8)))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(card) }
                addView(title); addView(detail)
                addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) })
            }
            return Row(box, title, detail, bar, back)
        }
        rows = linkedMapOf("wifi" to row("Wi-Fi"), "mobile" to row("Mobile data"))
        diag = text(13f, muted)
        val check = Button(this).apply {
            text = "Check both networks"; isAllCaps = false
            setOnClickListener { runCheck() }
        }
        val note = text(13f, muted).apply {
            text = "Each new connection goes out on Wi-Fi or mobile data, more of them on the faster one. Downloads, " +
                "browsers and streaming open many connections and get faster; one single connection cannot go faster " +
                "than one network. Mobile data used while merging counts against your plan."
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(bg)
            addView(text(28f, fg, bold = true).apply { text = "NetMerge" })
            addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(total, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(20) })
            for (r in rows.values) addView(r.box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(check, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20); gravity = Gravity.START })
            addView(diag)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
        // not when Android recreates the screen (it would replay an old start/stop)
        if (savedInstanceState == null) handleTestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestIntent(intent)
    }

    private fun handleTestIntent(i: Intent) {
        if (!i.hasExtra("merge")) return
        if (i.getBooleanExtra("merge", false)) startMerge() else stopMerge()
        i.removeExtra("merge")
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        last.clear()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val running = MergeVpnService.running
        toggle.text = if (running) "Stop merging" else "Start merging"
        toggle.background = GradientDrawable().apply {
            cornerRadius = 28 * resources.displayMetrics.density
            setColor(if (running) Color.rgb(200, 60, 60) else Color.rgb(30, 110, 230))
        }
        val up = upNow()
        if (!running) {
            state.text = when {
                up.size >= 2 -> "Ready: Wi-Fi and mobile data are both on"
                up.isEmpty() -> "No network"
                else -> "Only ${up.joinToString { if (it == "wifi") "Wi-Fi" else "mobile data" }} is on: turn on both to merge"
            }
            total.text = "Off"
            for ((name, r) in rows) {
                r.detail.text = if (name in up) "Connected" else "Not connected"
                setBar(r, 0.0)
            }
            return
        }
        val stats = runCatching { JSONObject(mobile.Mobile.stats()) }.getOrNull()
        val nets = stats?.optJSONArray("nets")
        val now = SystemClock.elapsedRealtime()
        var sum = 0.0
        val speed = HashMap<String, Double>()
        val share = HashMap<String, Double>()
        for (i in 0 until (nets?.length() ?: 0)) {
            val n = nets!!.getJSONObject(i)
            val name = n.getString("name")
            val rx = n.getLong("rx")
            val prev = last[name]
            val mbps = if (prev != null && now > prev.second && rx >= prev.first) (rx - prev.first) * 8.0 / ((now - prev.second) / 1000.0) / 1e6 else 0.0
            last[name] = rx to now
            speed[name] = mbps
            share[name] = n.optDouble("share", 0.0)
            sum += mbps
        }
        state.text = "Merging ${speed.size} network${if (speed.size == 1) "" else "s"}"
        total.text = "%.1f Mbps".format(sum)
        for ((name, r) in rows) {
            val s = speed[name]
            if (s == null) {
                r.detail.text = "Not in use (not connected)"
                setBar(r, 0.0)
            } else {
                val part = if (sum > 0.05) s / sum else share[name] ?: 0.0
                r.detail.text = "%.1f Mbps now · %d%% of new connections".format(s, ((share[name] ?: 0.0) * 100).toInt())
                setBar(r, part)
            }
        }
    }

    private fun setBar(r: Row, part: Double) {
        val w = r.barBack.width
        r.bar.layoutParams = LinearLayout.LayoutParams((w * part.coerceIn(0.0, 1.0)).toInt(), r.barBack.height.coerceAtLeast(1))
        r.bar.background = GradientDrawable().apply { cornerRadius = 4 * resources.displayMetrics.density; setColor(Color.rgb(30, 110, 230)) }
    }

    /** Which of Wi-Fi / mobile data are connected now, without asking Android to keep anything up. */
    private fun upNow(): Set<String> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val out = HashSet<String>()
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += "wifi"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += "mobile"
        }
        return out
    }

    private fun startMerge() {
        val consent = VpnService.prepare(this)
        pendingMerge = true
        if (consent != null) startActivityForResult(consent, 2) else onActivityResult(2, RESULT_OK, null)
    }

    private fun stopMerge() {
        startService(Intent(this, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP))
    }

    private fun runCheck() {
        diag.text = "Checking…"
        thread {
            val nets = DualNetworks(this)
            nets.start()
            nets.awaitBoth(15_000)
            val r = nets.report()
            nets.stop()
            runOnUiThread { diag.text = r }
        }
    }

    @Deprecated("plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2 && resultCode == RESULT_OK && pendingMerge) {
            pendingMerge = false
            val i = Intent(this, MergeVpnService::class.java).putExtra("mtu", intent.getIntExtra("mtu", MergeVpnService.DEFAULT_MTU))
            if (intent.hasExtra("ondemand")) i.putExtra("ondemand", intent.getBooleanExtra("ondemand", true))
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }
}
