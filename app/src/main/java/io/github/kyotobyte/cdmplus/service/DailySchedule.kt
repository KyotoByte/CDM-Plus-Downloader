package io.github.kyotobyte.cdmplus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.kyotobyte.cdmplus.CdmApp
import java.util.Calendar

/** Daily scheduler: resume all at one hour, pause all at another. Re-arms itself every day. */
object DailySchedule {
    const val ACTION_START = "io.github.kyotobyte.cdmplus.DAILY_START"
    const val ACTION_STOP = "io.github.kyotobyte.cdmplus.DAILY_STOP"

    fun arm(ctx: Context) {
        val s = CdmApp.instance.settings.value
        set(ctx, ACTION_START, s.dailyStartHour, 11)
        set(ctx, ACTION_STOP, s.dailyStopHour, 12)
    }

    private fun set(ctx: Context, action: String, hour: Int, code: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, code, Intent(ctx, DailyReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (hour !in 0..23) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        }
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = (context.applicationContext as CdmApp).core
        when (intent.action) {
            DailySchedule.ACTION_START -> core.resumeAll()
            DailySchedule.ACTION_STOP -> core.pauseAll()
        }
        DailySchedule.arm(context) // next day
    }
}
