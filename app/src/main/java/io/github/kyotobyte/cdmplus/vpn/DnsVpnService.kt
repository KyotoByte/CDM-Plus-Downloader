package io.github.kyotobyte.cdmplus.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.MainActivity
import io.github.kyotobyte.cdmplus.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * "1.1.1.1 mode": a local, DNS-only VPN. The tunnel routes just one virtual address (the DNS
 * server we hand to Android), so only DNS queries enter it; every other packet goes out as
 * normal. Queries for blocked domains are answered with 0.0.0.0 on the phone; the rest are
 * forwarded over DNS-over-HTTPS to the chosen resolver. CDM+ itself is excluded from the
 * tunnel, so downloads keep working (and use encrypted DNS on their own, see DohDns).
 * This is NOT a full traffic tunnel like Cloudflare WARP.
 */
class DnsVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var workers = Executors.newFixedThreadPool(8)
    private var ticker: ScheduledExecutorService? = null
    @Volatile private var core: DnsTunnelCore? = null
    @Volatile var resolver: DohResolver? = null
        private set

    private val repo get() = CdmApp.instance.vpn

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); stopSelf(); return START_NOT_STICKY }
        if (tun == null) start() else reload()
        return START_STICKY
    }

    private fun start() {
        val cfg = repo.config.value
        val doh = DohResolver(cfg.upstream)
        resolver = doh
        val filter = if (repo.filter === DnsFilter.NONE) repo.rebuildFilter() else repo.filter
        val c = DnsTunnelCore(filter, doh::resolve, repo.stats)
        core = c
        val b = Builder()
            .setSession("CDM+ 1.1.1.1")
            .addAddress(TUN_ADDR, 32)
            .addRoute(DNS_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .setMtu(1500)
            .setBlocking(true)
            .setConfigureIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "vpn"), PendingIntent.FLAG_IMMUTABLE))
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = runCatching { b.establish() }.getOrNull()
        if (fd == null) { stopSelf(); return }
        tun = fd
        current = this
        repo.running.value = true
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        if (workers.isShutdown) workers = Executors.newFixedThreadPool(8)
        reader = Thread({
            val buf = ByteArray(32767)
            while (!Thread.currentThread().isInterrupted) {
                val n = try { input.read(buf) } catch (e: Exception) { break }
                if (n <= 0) { if (n < 0) break; continue }
                val pkt = buf.copyOf(n)
                runCatching {
                    workers.execute {
                        val reply = runCatching { core?.handle(pkt, n) }.getOrNull() ?: return@execute
                        runCatching { synchronized(output) { output.write(reply) } }
                    }
                }
            }
        }, "cdm-dns-tunnel").apply { isDaemon = true; start() }
        ticker = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({ runCatching { notifyStats() } }, 0, 5, TimeUnit.SECONDS)
        }
    }

    /** Applies a changed resolver or block list without dropping the tunnel. */
    fun reload() {
        val cfg = repo.config.value
        if (resolver?.upstream != cfg.upstream) {
            val doh = DohResolver(cfg.upstream); resolver = doh; core?.resolver = doh::resolve
        }
        core?.filter = repo.filter
        notifyStats()
    }

    private fun notifyStats() {
        val s = repo.stats
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val stop = PendingIntent.getService(this, 2, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "vpn"), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CdmApp.CH_VPN) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("1.1.1.1 mode ON · ${repo.config.value.upstream.title}")
            .setContentText("${s.blocked.get()} blocked of ${s.queries.get()} lookups · DNS encrypted")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun shutdown() {
        ticker?.shutdownNow(); ticker = null
        reader?.interrupt(); reader = null
        runCatching { tun?.close() }; tun = null
        workers.shutdownNow()
        core = null; resolver = null
        if (current === this) current = null
        repo.running.value = false
        repo.saveLifetime()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    override fun onRevoke() { shutdown(); repo.update { it.copy(wanted = false) }; stopSelf() }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    companion object {
        const val ACTION_START = "io.github.kyotobyte.cdmplus.vpn.START"
        const val ACTION_STOP = "io.github.kyotobyte.cdmplus.vpn.STOP"
        const val TUN_ADDR = "10.111.222.1"
        const val DNS_ADDR = "10.111.222.2"
        const val NOTIF_ID = 7711
        @Volatile var current: DnsVpnService? = null
            private set

        /** True when Android already has the user's VPN consent for CDM+. */
        fun hasConsent(ctx: Context) = prepare(ctx) == null

        fun start(ctx: Context) {
            CdmApp.instance.vpn.update { it.copy(wanted = true) }
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            CdmApp.instance.vpn.update { it.copy(wanted = false) }
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
