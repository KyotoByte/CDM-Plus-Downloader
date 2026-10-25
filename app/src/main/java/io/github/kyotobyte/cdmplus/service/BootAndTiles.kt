package io.github.kyotobyte.cdmplus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.engine.Status
import io.github.kyotobyte.cdmplus.vpn.DnsVpnService

/** After a reboot: resume the queue, re-arm schedules, and bring 1.1.1.1 mode back if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ALLOWED) return // exported for the system: ignore anything else
        val app = context.applicationContext as CdmApp // onCreate already restarted the queue
        app.core.releaseDue()
        if (app.vpn.config.value.wanted && DnsVpnService.hasConsent(context)) runCatching { DnsVpnService.start(context) }
        FloatingService.sync(context)
        DailySchedule.arm(context)
    }

    companion object {
        val ALLOWED = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON")
    }
}

/** Quick-settings tile: pause or resume all downloads. */
class DownloadsTileService : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val core = CdmApp.instance.core
        if (core.items.value.any { it.status == Status.RUNNING || it.status == Status.QUEUED }) core.pauseAll() else core.resumeAll()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val items = CdmApp.instance.core.items.value
        val active = items.count { it.status == Status.RUNNING || it.status == Status.QUEUED }
        t.state = if (active > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "CDM+"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (active > 0) "$active active · tap to pause" else "Tap to resume all"
        t.updateTile()
    }
}
