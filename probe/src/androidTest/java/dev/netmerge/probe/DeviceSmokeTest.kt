package dev.netmerge.probe

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * For phones nobody is holding (Firebase Test Lab): NetMerge starts, Android's own questions (may it run a VPN, may
 * it show notifications) are answered by tapping, the VPN comes up and pages open through it; then each screen is
 * photographed, merging stops and pages still open. Wi-Fi only on those phones: merging itself is tested elsewhere.
 * Screenshots go to the probe's external files folder (pulled by the test run).
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.context
    private val dev = UiDevice.getInstance(inst)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val shots = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }

    private fun report(key: String, value: String) =
        inst.sendStatus(0, Bundle().apply { putString(key, value) })

    private fun vpnUp() = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }

    private fun open(vararg extras: Pair<String, Any>) {
        val i = Intent().setClassName("dev.netmerge.android", "dev.netmerge.android.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        for ((k, v) in extras) when (v) { is Boolean -> i.putExtra(k, v); is String -> i.putExtra(k, v) }
        ctx.startActivity(i)
    }

    /** Taps Android's own question buttons (VPN consent: OK; notifications: Allow) while they show. */
    private fun answerQuestions() {
        dev.findObject(By.res("android:id/button1"))?.click()
        dev.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()
        dev.findObject(By.text(Pattern.compile("(?i)^(ok|allow|continue)$")))?.click()
    }

    private fun waitFor(secs: Int, what: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + secs * 1000L
        while (SystemClock.elapsedRealtime() < until) {
            if (what()) return true
            answerQuestions()
            SystemClock.sleep(500)
        }
        return what()
    }

    private fun shot(name: String) {
        SystemClock.sleep(1500)
        dev.takeScreenshot(File(shots, "$name.png"))
    }

    @Test
    fun startsMergesAndStops() {
        report("phone", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        open("merge" to false, "tab" to "home")
        SystemClock.sleep(3000)
        answerQuestions()
        open("merge" to true)
        if (!waitFor(45) { vpnUp() }) {
            // say what the phone shows instead (another maker's VPN question, a blocked start, an error)
            shot("no-vpn")
            val seen = dev.findObjects(By.text(Pattern.compile(".+"))).joinToString(" | ") { it.text }.take(600)
            val buttons = dev.findObjects(By.clickable(true)).mapNotNull { o -> o.text ?: o.contentDescription ?: o.resourceName }.joinToString(", ").take(400)
            report("screen", "package ${dev.currentPackageName}; text: $seen; buttons: $buttons")
            runCatching { dev.dumpWindowHierarchy(File(shots, "no-vpn.xml")) }
            throw AssertionError("the VPN did not come up within 45 s; on screen: ${dev.currentPackageName}: $seen")
        }
        report("vpn", "up")
        SystemClock.sleep(3000)
        ProbeTest().pagesOpen()
        report("pages", "open through NetMerge")
        for (t in listOf("home", "test", "block", "settings")) { open("tab" to t); shot("merging-$t") }
        open("merge" to false)
        assertTrue("the VPN did not go away after stop", waitFor(20) { !vpnUp() })
        ProbeTest().pagesOpen()
        report("pages", "open after stop")
        open("tab" to "home"); shot("off-home")
        assertFalse(vpnUp())
    }
}
