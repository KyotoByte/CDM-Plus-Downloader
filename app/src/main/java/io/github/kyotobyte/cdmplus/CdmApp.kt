package io.github.kyotobyte.cdmplus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.github.kyotobyte.cdmplus.data.SettingsRepo
import io.github.kyotobyte.cdmplus.engine.DownloadCore
import io.github.kyotobyte.cdmplus.engine.DownloadStore
import io.github.kyotobyte.cdmplus.engine.EngineSettings
import io.github.kyotobyte.cdmplus.service.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class CdmApp : Application() {
    /** Safety net: a background job that fails unexpectedly is logged, never crashes the app. */
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("CDM+", "background task failed", e) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    lateinit var settings: SettingsRepo
        private set
    lateinit var core: DownloadCore
        private set
    lateinit var browserStore: io.github.kyotobyte.cdmplus.data.BrowserStore
        private set
    lateinit var vpn: io.github.kyotobyte.cdmplus.vpn.VpnRepo
        private set
    /** Plain client for block-list updates. */
    val listClient: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepo(this)
        browserStore = io.github.kyotobyte.cdmplus.data.BrowserStore(this)
        vpn = io.github.kyotobyte.cdmplus.vpn.VpnRepo(this)
        appScope.launch { vpn.rebuildFilter() } // also powers the browser's ad-block while 1.1.1.1 is on
        io.github.kyotobyte.cdmplus.data.AdBlock.init(this)
        // Warm up the WebView engine once the app is idle, so the first browser page opens faster.
        android.os.Looper.myQueue().addIdleHandler { runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }; false }
        createChannels()
        core = DownloadCore(appScope, DownloadStore(File(filesDir, "downloads.json")), buildClient(settings.value), AndroidPlatform(this)) {
            val s = settings.value
            EngineSettings(s.threads, s.maxSimultaneous, s.speedLimitKb * 1024L, s.autoResume, s.userAgent, s.retryCount, s.retryDelaySec, s.domainPartMap(), s.seedEnabled, s.seedRatio, s.seedMinutes, s.uploadLimitKb * 1024)
        }
        var proxyKey = Triple(settings.value.proxyHost, settings.value.proxyPort, settings.value.proxySocks)
        appScope.launch {
            settings.state.drop(1).collect { s ->
                if (Triple(s.proxyHost, s.proxyPort, s.proxySocks) != proxyKey) { proxyKey = Triple(s.proxyHost, s.proxyPort, s.proxySocks); core.setClient(buildClient(s)) }
                io.github.kyotobyte.cdmplus.ui.Px.amoled = s.amoled; io.github.kyotobyte.cdmplus.ui.Px.light = s.themeMode == "light"
                io.github.kyotobyte.cdmplus.service.FloatingService.sync(this@CdmApp)
                io.github.kyotobyte.cdmplus.service.DailySchedule.arm(this@CdmApp)
                io.github.kyotobyte.cdmplus.update.Updater.onSettingsChanged(this@CdmApp)
                core.onNetworkChanged()
            }
        }
        io.github.kyotobyte.cdmplus.ui.Px.amoled = settings.value.amoled; io.github.kyotobyte.cdmplus.ui.Px.light = settings.value.themeMode == "light"
        watchNetwork()
        runCatching { io.github.kyotobyte.cdmplus.service.DailySchedule.arm(this) }
        // Battery level changes re-check the "stop when battery is low" rule.
        runCatching {
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) { if (settings.value.batteryStop > 0) core.onNetworkChanged() }
            }, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }
        core.start()
        io.github.kyotobyte.cdmplus.update.Updater.init(this)
    }

    /**
     * Download client. HTTP/1.1 on purpose: each part gets its own TCP connection,
     * which is what makes multi-connection downloads faster (HTTP/2 would multiplex
     * every part onto one socket). Connection pool sized for 32 parallel parts.
     */
    fun buildClient(s: io.github.kyotobyte.cdmplus.data.AppSettings): OkHttpClient {
        val b = OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Encrypted DNS for downloads while 1.1.1.1 mode is on (falls back to the system resolver).
            .dns(io.github.kyotobyte.cdmplus.vpn.DohDns {
                if (::vpn.isInitialized && vpn.config.value.encryptDownloads) io.github.kyotobyte.cdmplus.vpn.DnsVpnService.current?.resolver else null
            })
            .connectionPool(okhttp3.ConnectionPool(40, 5, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 40 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        if (s.proxyHost.isNotBlank() && s.proxyPort in 1..65535) {
            // HTTP or SOCKS5 (Tor via Orbot = SOCKS 127.0.0.1:9050)
            b.proxy(java.net.Proxy(if (s.proxySocks) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved(s.proxyHost.trim(), s.proxyPort)))
        }
        return b.build()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { core.onNetworkChanged() }
                override fun onLost(network: Network) { core.onNetworkChanged() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { core.onNetworkChanged() }
            })
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_VPN, "1.1.1.1 mode", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val CH_PROGRESS = "cdm_progress"
        const val CH_DONE = "cdm_done"
        const val CH_VPN = "cdm_vpn"
        lateinit var instance: CdmApp
            private set
    }
}
