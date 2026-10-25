package io.github.kyotobyte.cdmplus.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.MainActivity

/** Quick-settings tile: one tap turns 1.1.1.1 mode on or off. */
class VpnTileService : TileService() {
    override fun onStartListening() { refresh() }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (CdmApp.instance.vpn.running.value) {
            DnsVpnService.stop(this)
        } else if (DnsVpnService.hasConsent(this)) {
            DnsVpnService.start(this)
        } else {
            // First time: Android has to show its VPN consent dialog from an activity.
            val i = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "vpn")
                .putExtra(MainActivity.EXTRA_VPN_ON, true).putExtra(MainActivity.EXTRA_VPN_TOKEN, MainActivity.vpnToken(this)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
        qsTile?.let { it.state = if (CdmApp.instance.vpn.running.value) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; it.updateTile() }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = CdmApp.instance.vpn.running.value
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "1.1.1.1"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Ad-block ON" else "Off"
        t.updateTile()
    }
}
