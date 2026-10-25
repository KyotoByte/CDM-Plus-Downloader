package io.github.kyotobyte.cdmplus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kyotobyte.cdmplus.CdmApp

/** Fires when a scheduled download is due. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as CdmApp).core.releaseDue()
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = (context.applicationContext as CdmApp).core
        when (intent.action) {
            ACTION_PAUSE_ALL -> core.pauseAll()
        }
    }
    companion object { const val ACTION_PAUSE_ALL = "io.github.kyotobyte.cdmplus.PAUSE_ALL" }
}
