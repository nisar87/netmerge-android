package dev.netmerge.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Apps left out of merging: they use the phone's normal network as if NetMerge were off (for apps that do not like a
 * VPN, such as some banking apps or games). Takes effect the next time merging starts.
 */
class AppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) Color.rgb(236, 238, 241) else Color.rgb(24, 28, 33)
        val muted = if (dark) Color.rgb(150, 156, 165) else Color.rgb(96, 102, 112)
        val bg = if (dark) Color.rgb(18, 20, 24) else Color.WHITE
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (28 * d).toInt(), (20 * d).toInt(), (28 * d).toInt())
            setBackgroundColor(bg)
            addView(TextView(this@AppsActivity).apply { text = "Apps left out of merging"; textSize = 24f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(this@AppsActivity).apply {
                text = "Ticked apps use the phone's normal network, as if NetMerge were off. Use it for apps that do " +
                    "not work with a VPN. Changes apply the next time merging starts."
                textSize = 14f; setTextColor(muted); setPadding(0, (6 * d).toInt(), 0, (12 * d).toInt())
            })
        }
        val loading = TextView(this).apply { text = "Loading apps…"; setTextColor(muted) }
        col.addView(loading)
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
        thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
            runOnUiThread {
                col.removeView(loading)
                val left = Excluded.get(this)
                for ((pkg, label) in apps) {
                    col.addView(CheckBox(this).apply {
                        text = label; textSize = 16f; setTextColor(fg); gravity = Gravity.CENTER_VERTICAL
                        isChecked = pkg in left
                        setPadding((8 * d).toInt(), (10 * d).toInt(), 0, (10 * d).toInt())
                        setOnCheckedChangeListener { _, on -> Excluded.set(this@AppsActivity, pkg, on) }
                    })
                }
            }
        }
    }
}

/** The apps left out of merging (package names). */
object Excluded {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(c: Context): Set<String> = p(c).getStringSet("excludedApps", emptySet()) ?: emptySet()
    fun set(c: Context, pkg: String, on: Boolean) {
        val now = get(c).toMutableSet()
        if (on) now += pkg else now -= pkg
        p(c).edit().putStringSet("excludedApps", now).apply()
    }
}
