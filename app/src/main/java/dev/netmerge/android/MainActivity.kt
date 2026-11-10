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
        val stop = Button(this).apply {
            text = "Stop test VPN"
            setOnClickListener { startService(Intent(this@MainActivity, SpikeVpnService::class.java).setAction(SpikeVpnService.ACTION_STOP)) }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(check); addView(vpn); addView(stop); addView(out)
            })
        })
        runCheck()
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
