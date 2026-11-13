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
        note("tile: clicked, running=${MergeVpnService.running}, prepared=${VpnService.prepare(this) == null}")
        if (MergeVpnService.running) {
            logged("stop merging from the tile") { startService(Intent(this, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP)) }
        } else if (VpnService.prepare(this) == null && runCatching {
                startForegroundService(Intent(this, MergeVpnService::class.java))
            }.onFailure { note("tile: could not start the service: $it") }.isSuccess) {
            note("tile: service asked to start")
        } else {
            // Android must ask once whether NetMerge may run a VPN, or (newer Android) does not let a tile start a
            // foreground service while the app is in the background: the app's screen starts it then. Seen on a
            // Galaxy S26 Ultra (Android 17): the tile stopped merging but could not start it.
            note("tile: opening the app to start merging")
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("merge", true)
            if (Build.VERSION.SDK_INT >= 34) {
                logged("open the app from the tile") { startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE)) }
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                logged("open the app from the tile") { startActivityAndCollapse(i) }
            }
        }
    }

    /** Into the same events file as the service (tests read it). */
    private fun note(what: String) = EventLog.add(this, what)

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
