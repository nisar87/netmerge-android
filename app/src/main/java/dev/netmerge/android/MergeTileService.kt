package dev.netmerge.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The quick settings tile: merging on or off with one tap from the notification shade. */
class MergeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        if (MergeVpnService.running) {
            startService(Intent(this, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP))
        } else if (VpnService.prepare(this) == null) {
            startForegroundService(Intent(this, MergeVpnService::class.java))
        } else {
            // Android must ask once whether NetMerge may run a VPN: that needs the app's screen
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("merge", true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(i)
            }
        }
    }

    private fun show() {
        val t = qsTile ?: return
        t.state = if (MergeVpnService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "NetMerge"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (MergeVpnService.running) "Merging" else "Off"
        t.updateTile()
    }

    companion object {
        fun update(c: Context) {
            runCatching { requestListeningState(c, ComponentName(c, MergeTileService::class.java)) }
        }
    }
}
