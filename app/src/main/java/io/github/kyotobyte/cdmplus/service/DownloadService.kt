package io.github.kyotobyte.cdmplus.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.MainActivity
import io.github.kyotobyte.cdmplus.R
import io.github.kyotobyte.cdmplus.engine.Status
import io.github.kyotobyte.cdmplus.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps downloads alive in the background and shows progress + speed. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build("Starting downloads…", "", 0, true), type)
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background (e.g. restart after the
            // process was killed). Downloads stay queued and resume when the app is opened.
            running = false; stopSelf(); return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cdmplus:download").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        val core = (application as CdmApp).core
        scope.launch {
            var idleTicks = 0
            while (isActive) {
                val items = core.items.value
                val running = items.filter { it.status == Status.RUNNING }
                val queued = items.count { it.status == Status.QUEUED }
                if (running.isEmpty() && queued == 0) {
                    if (++idleTicks >= 2) { stopSelfClean(); break }
                } else idleTicks = 0
                val speed = core.speeds.value.values.sum()
                val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
                val done = running.sumOf { it.downloadedBytes }
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                val title = when {
                    running.size == 1 -> running[0].fileName
                    running.isNotEmpty() -> "${running.size} downloads running"
                    else -> items.firstOrNull { it.status == Status.QUEUED }?.waitingReason ?: "Queued"
                }
                val text = buildString {
                    if (running.isNotEmpty()) append("${formatBytes(speed)}/s · ${formatBytes(done)}${if (total > 0) " / " + formatBytes(total) else ""}")
                    if (queued > 0) append(if (isEmpty()) "$queued queued" else " · $queued queued")
                }
                if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this@DownloadService, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { NotificationManagerCompat.from(this@DownloadService).notify(NOTIF_ID, build(title, text, pct, total <= 0 && running.isNotEmpty())) } catch (_: SecurityException) {}
                }
                delay(1000)
            }
        }
    }

    /** Android 15+: dataSync foreground services get ~6 h per day; stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as CdmApp).core.pauseAll()
        stopSelfClean()
    }

    @Deprecated("Android 14 variant")
    override fun onTimeout(startId: Int) {
        (application as CdmApp).core.pauseAll()
        stopSelfClean()
    }

    private fun stopSelfClean() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun build(title: String, text: String, pct: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CdmApp.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Pause all", PendingIntent.getBroadcast(this, 1, Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE))
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4242
        @Volatile var running = false
    }
}
