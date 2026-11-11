package dev.netmerge.android

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** A plain test screen: keep both networks up, check each one, start or stop the test VPN. */
class MainActivity : Activity() {
    private lateinit var nets: DualNetworks
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nets = DualNetworks(this).also { it.start() }
        out = TextView(this).apply { setPadding(32, 32, 32, 32); textSize = 14f; text = "Waiting for Wi-Fi and mobile data…" }
        val check = Button(this).apply { text = "Check both networks"; setOnClickListener { runCheck() } }
        val vpn = Button(this).apply { text = "Start test VPN"; setOnClickListener { startVpn() } }
        val merge = Button(this).apply { text = "Start merging"; setOnClickListener { startMerge() } }
        val unmerge = Button(this).apply {
            text = "Stop merging"
            setOnClickListener { startService(Intent(this@MainActivity, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP)) }
        }
        val stop = Button(this).apply {
            text = "Stop test VPN"
            setOnClickListener { startService(Intent(this@MainActivity, SpikeVpnService::class.java).setAction(SpikeVpnService.ACTION_STOP)) }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(merge); addView(unmerge); addView(check); addView(vpn); addView(stop); addView(out)
            })
        })
        runCheck()
        // tests: adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true|false
        if (intent.hasExtra("merge")) {
            if (intent.getBooleanExtra("merge", false)) startMerge()
            else startService(Intent(this, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP))
        }
    }

    private var pendingMerge = false

    private fun startMerge() {
        val consent = VpnService.prepare(this)
        pendingMerge = true
        if (consent != null) startActivityForResult(consent, 2) else onActivityResult(2, RESULT_OK, null)
    }

    private fun runCheck() {
        out.text = "Checking…"
        thread {
            nets.awaitBoth(15_000)
            val r = nets.report()
            runOnUiThread { out.text = r }
        }
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, 1) else onActivityResult(1, RESULT_OK, null)
    }

    @Deprecated("plain Activity API, enough for a test screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2 && resultCode == RESULT_OK && pendingMerge) {
            pendingMerge = false
            startService(Intent(this, MergeVpnService::class.java).putExtra("mtu", intent.getIntExtra("mtu", MergeVpnService.DEFAULT_MTU)))
            out.text = "Merging started"
            return
        }
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startService(Intent(this, SpikeVpnService::class.java))
            out.postDelayed({ runCheck() }, 1500)
        }
    }

    override fun onDestroy() {
        nets.stop()
        super.onDestroy()
    }
}
