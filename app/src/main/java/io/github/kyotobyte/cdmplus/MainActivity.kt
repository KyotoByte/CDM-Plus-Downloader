package io.github.kyotobyte.cdmplus

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.kyotobyte.cdmplus.engine.DownloadItem
import io.github.kyotobyte.cdmplus.ui.AppState
import io.github.kyotobyte.cdmplus.ui.CdmRoot
import io.github.kyotobyte.cdmplus.ui.CdmTheme
import io.github.kyotobyte.cdmplus.ui.DownloadActions
import io.github.kyotobyte.cdmplus.ui.extractUrls
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val app get() = application as CdmApp
    private val state = AppState()
    private val clipboardUrl = mutableStateOf<String?>(null)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            app.settings.update { it.copy(saveTreeUri = uri.toString()) }
            state.toast = "Save folder set"
        }
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(io.github.kyotobyte.cdmplus.data.ListBackup.export(app.core.items.value).toByteArray()) }
            state.toast = "Exported ${app.core.items.value.size} downloads"
        }.onFailure { state.toast = "Export failed" }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
            val entries = io.github.kyotobyte.cdmplus.data.ListBackup.parse(text)
            entries.forEach { e -> app.core.add(e.url, e.fileName) }
            state.toast = "Imported ${entries.size} links"
        }.onFailure { state.toast = "Import failed" }
    }

    private val qrLauncher = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        val urls = extractUrls(text)
        if (urls.isNotEmpty()) state.pendingAdd = urls.joinToString("\n") else state.toast = "QR code has no link: ${text.take(40)}"
    }
    private val vault by lazy { io.github.kyotobyte.cdmplus.data.PasswordVault(java.io.File(filesDir, "vault.bin")) { io.github.kyotobyte.cdmplus.service.KeystoreKey.get() } }
    private val social by lazy { io.github.kyotobyte.cdmplus.grab.SocialGrabber(app.core.client) }
    private val crawler get() = io.github.kyotobyte.cdmplus.engine.LinkCrawler(app.core.client)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == io.github.kyotobyte.cdmplus.service.BioLock.REQ_CONFIRM) io.github.kyotobyte.cdmplus.service.BioLock.onConfirmResult(resultCode == RESULT_OK)
    }

    private fun grabDownload(reqs: List<io.github.kyotobyte.cdmplus.ui.GrabRequest>) {
        reqs.forEach { r ->
            // Grabs from the in-app browser carry no headers: use the browser's own cookies for the media host.
            val headers = r.headers.ifEmpty {
                buildMap {
                    android.webkit.CookieManager.getInstance().getCookie(r.url)?.let { put("Cookie", it) }
                    put("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity))
                    when (r.source) { "TikTok" -> put("Referer", "https://www.tiktok.com/"); "Instagram" -> put("Referer", "https://www.instagram.com/"); "Facebook" -> put("Referer", "https://www.facebook.com/"); "X" -> put("Referer", "https://x.com/") }
                }
            }
            app.core.add(r.url, r.fileName, null, headers, null, r.mime, 0, null, r.audioUrl, r.postProcess, r.source, subDir = r.subDir)
        }
        state.tab = io.github.kyotobyte.cdmplus.ui.Tab.DOWNLOADS
    }

    private val vaultUi = object : io.github.kyotobyte.cdmplus.ui.VaultUi {
        override fun enabled() = app.settings.value.savePasswords
        override fun hasLogin(url: String) = runCatching { vault.forSite(url).isNotEmpty() }.getOrDefault(false)
        override fun isSame(url: String, user: String, pass: String) = runCatching { vault.isSame(url, user, pass) }.getOrDefault(false)
        override fun save(url: String, user: String, pass: String) { runCatching { vault.save(url, user, pass) }.onFailure { state.toast = "Couldn't save the password" } }
        override fun fill(url: String, run: (String) -> Unit) {
            io.github.kyotobyte.cdmplus.service.BioLock.unlock(this@MainActivity, io.github.kyotobyte.cdmplus.data.PasswordVault.siteOf(url)) { ok ->
                if (!ok) { state.toast = "Locked"; return@unlock }
                val l = vault.forSite(url).maxByOrNull { it.updatedAt } ?: return@unlock
                run(io.github.kyotobyte.cdmplus.data.PasswordVault.fillScript(l.username, l.password))
            }
        }
    }

    private val exportSettingsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(app.settings.exportJson().toByteArray()) }
            state.toast = "Settings exported"
        }.onFailure { state.toast = "Export failed" }
    }
    private val importSettingsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val n = app.settings.importJson(contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            state.toast = "Imported $n settings"
        }.onFailure { state.toast = "That isn't a CDM+ settings file" }
    }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startVpn() else state.toast = "VPN permission needed for 1.1.1.1 mode"
    }

    private fun toggleVpn(on: Boolean) {
        if (!on) { io.github.kyotobyte.cdmplus.vpn.DnsVpnService.stop(this); return }
        val consent = android.net.VpnService.prepare(this)
        if (consent != null) runCatching { vpnConsent.launch(consent) }.onFailure { state.toast = "This phone blocked the VPN request" }
        else startVpn()
    }

    private fun startVpn() {
        val vpn = app.vpn
        io.github.kyotobyte.cdmplus.vpn.DnsVpnService.start(this)
        // First run: fetch the switched-on lists, then the running tunnel picks them up.
        app.appScope.launch {
            vpn.updateMissingOrStale(app.listClient)
            io.github.kyotobyte.cdmplus.vpn.DnsVpnService.current?.reload()
        }
    }

    /** Always the highest refresh rate at the current resolution (native, no setting). */
    private fun applyRefreshRate() {
        io.github.kyotobyte.cdmplus.service.DisplayRate.apply(window, io.github.kyotobyte.cdmplus.service.DisplayRate.displayOf(this))
    }

    override fun onResume() {
        super.onResume()
        applyRefreshRate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, state.tab.name)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyRefreshRate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyRefreshRate() // fold/unfold, rotation, display switch: the mode list changes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Recreated by the system (font/locale change, process restore): come back to the same screen.
        savedInstanceState?.getString(STATE_TAB)?.let { n -> runCatching { state.tab = io.github.kyotobyte.cdmplus.ui.Tab.valueOf(n) } }
        askPermissions()
        handleIntent(intent)
        val actions = object : DownloadActions {
            override fun pause(id: String) = app.core.pause(id)
            override fun resume(id: String) = app.core.resume(id)
            override fun retry(id: String) = app.core.retry(id)
            override fun cancel(id: String) = app.core.cancel(id)
            override fun open(item: DownloadItem) = openItem(item)
            override fun share(item: DownloadItem) = shareItem(item)
            override fun delete(item: DownloadItem, withFile: Boolean) = app.core.delete(item.id, withFile)
            override fun relink(item: DownloadItem, url: String) {
                val u = extractUrls(url).firstOrNull() ?: url.trim()
                if (u.startsWith("http") || u.startsWith("ftp://")) app.core.updateLink(item.id, u) else state.toast = "That isn't a link"
            }
            override fun checksum(item: DownloadItem, onResult: (io.github.kyotobyte.cdmplus.engine.Checksums.Result?) -> Unit) {
                app.appScope.launch {
                    val r = runCatching {
                        val uri = item.savedUri?.let(Uri::parse)
                        val input = if (uri != null) contentResolver.openInputStream(uri) else item.savedPath?.let { java.io.File(it).inputStream() }
                        input?.let { io.github.kyotobyte.cdmplus.engine.Checksums.of(it) }
                    }.getOrNull()
                    runOnUiThread { onResult(r) }
                }
            }
            override fun extract(item: DownloadItem) {
                state.toast = "Extracting ${item.fileName}..."
                app.appScope.launch {
                    val n = runCatching {
                        val uri = item.savedUri?.let(Uri::parse)
                        val input = (if (uri != null) contentResolver.openInputStream(uri) else item.savedPath?.let { java.io.File(it).inputStream() }) ?: error("missing")
                        val tmp = java.io.File(cacheDir, "unzip-" + System.nanoTime())
                        val base = item.fileName.substringBeforeLast('.')
                        val platform = io.github.kyotobyte.cdmplus.service.AndroidPlatform(app)
                        val count = io.github.kyotobyte.cdmplus.engine.ZipExtract.extract(input, tmp) { f ->
                            val rel = f.parentFile!!.relativeTo(tmp).path
                            platform.publishLoose(f, if (rel.isEmpty()) base else "$base/$rel")
                        }
                        tmp.deleteRecursively(); count
                    }.getOrElse { -1 }
                    runOnUiThread { state.toast = if (n >= 0) "Extracted $n files to Downloads/CDM+/${item.fileName.substringBeforeLast('.')}" else "Could not extract this zip" }
                }
            }
            override fun stopSeeding(id: String) = app.core.stopSeeding(id)
            override fun extractMusic(item: DownloadItem) {
                state.toast = "Extracting music…"
                app.appScope.launch {
                    val name = runCatching {
                        val uri = item.savedUri?.let(Uri::parse)
                        val input = (if (uri != null) contentResolver.openInputStream(uri) else item.savedPath?.let { java.io.File(it).inputStream() }) ?: error("missing")
                        val src = java.io.File(cacheDir, "mx-" + System.nanoTime())
                        input.use { i -> src.outputStream().use { i.copyTo(it) } }
                        val out = java.io.File(cacheDir, "ma-" + System.nanoTime())
                        val ext = io.github.kyotobyte.cdmplus.service.MediaTools.extractAudio(src, out) ?: run { src.delete(); error("no audio") }
                        val named = java.io.File(cacheDir, item.fileName.substringBeforeLast('.') + "." + ext)
                        out.renameTo(named); src.delete()
                        io.github.kyotobyte.cdmplus.service.AndroidPlatform(app).publishLoose(named, "Music")
                        named.delete(); named.name
                    }.getOrNull()
                    runOnUiThread { state.toast = if (name != null) "Saved Downloads/CDM+/Music/$name" else "No music track found in this video" }
                }
            }
            override fun clipboardText(): String = runCatching {
                getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            }.getOrNull() ?: ""
        }
        setContent {
            val settings by app.settings.state.collectAsState()
            val items by app.core.items.collectAsState()
            val speeds by app.core.speeds.collectAsState()
            val history by app.core.speedHistory.collectAsState()
            val clip by clipboardUrl
            val splash = remember { savedInstanceState == null }
            CdmTheme(settings.accent, settings.customAccent) {
                CdmRoot(
                    state = state, items = items, speeds = speeds, settings = settings,
                    onSettings = { f -> app.settings.update(f) }, actions = actions,
                    onAdd = { req, headers, mime ->
                        req.urls.forEach { url -> app.core.add(url, req.fileName, req.threads, headers, req.scheduledAt, mime, req.speedLimitKb * 1024L) }
                    },
                    clipboardUrl = clip,
                    onClipboardDismiss = { clip?.let { app.settings.setLastClipboard(it) }; clipboardUrl.value = null },
                    showSplash = splash,
                    history = history,
                    browserStore = app.browserStore,
                    onPickFolder = { runCatching { folderLauncher.launch(null) } },
                    onExport = { exportLauncher.launch("cdmplus-downloads.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onExportSettings = { exportSettingsLauncher.launch("cdmplus-settings.json") },
                    extras = io.github.kyotobyte.cdmplus.ui.Extras(
                        grab = { u, _ ->
                            if (io.github.kyotobyte.cdmplus.grab.MegaGrabber.isSupported(u)) io.github.kyotobyte.cdmplus.grab.MegaGrabber.grab(u, app.core.client)
                            else if (io.github.kyotobyte.cdmplus.grab.MediaSiteGrabber.isSupported(u)) io.github.kyotobyte.cdmplus.grab.MediaSiteGrabber.grab(u, app.core.client)
                            else social.grab(u)
                        },
                        onGrabDownload = { grabDownload(it) },
                        crawl = { u, o, cb -> crawler.crawl(u, o, cb) },
                        torrentFiles = { id, u -> app.core.torrents.files(DownloadItem(id, u, "torrent"), app.core.client) },
                        addTorrent = { id, u, files -> app.core.add(u, torrentFiles = files, id0 = id) },
                        scanQr = { qrLauncher.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE).setPrompt("Scan a QR code with a link").setBeepEnabled(false).setOrientationLocked(false)) },
                        vault = vaultUi,
                        passwordsContent = { io.github.kyotobyte.cdmplus.ui.PasswordList(vault, { reason, cb -> io.github.kyotobyte.cdmplus.service.BioLock.unlock(this@MainActivity, reason, cb) }) },
                    ),
                    onImportSettings = { importSettingsLauncher.launch(arrayOf("application/json", "*/*")) },
                    onFloating = { on ->
                        if (on && !android.provider.Settings.canDrawOverlays(this)) {
                            state.toast = "Allow \"display over other apps\" for CDM+, then switch it on again"
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        } else app.settings.update { it.copy(floating = on) }
                    },
                    vpnContent = {
                        val vcfg by app.vpn.config.collectAsState()
                        val vlists by app.vpn.lists.collectAsState()
                        val vrun by app.vpn.running.collectAsState()
                        var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                        androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
                        val st = app.vpn.stats
                        io.github.kyotobyte.cdmplus.ui.VpnScreen(
                            io.github.kyotobyte.cdmplus.ui.VpnUiState(vrun, vcfg, vlists, st.queries.get(), st.blocked.get(), app.vpn.lifetimeBlocked, st.recentBlocked(), now.coerceAtLeast(1)),
                            onToggle = { toggleVpn(it) },
                            onConfig = { f ->
                                app.vpn.update(f)
                                io.github.kyotobyte.cdmplus.vpn.DnsVpnService.current?.reload()
                                app.appScope.launch { app.vpn.updateMissingOrStale(app.listClient); io.github.kyotobyte.cdmplus.vpn.DnsVpnService.current?.reload() }
                            },
                            onUpdate = { src ->
                                app.appScope.launch {
                                    val all = src?.let { listOf(it) } ?: io.github.kyotobyte.cdmplus.vpn.BlockSource.entries.filter { it.id in app.vpn.config.value.lists }
                                    all.forEach { app.vpn.updateList(it, app.listClient) }
                                    io.github.kyotobyte.cdmplus.vpn.DnsVpnService.current?.reload()
                                }
                            },
                        )
                    },
                )
                io.github.kyotobyte.cdmplus.update.UpdateDialogHost()
            }
        }
        if (savedInstanceState == null) io.github.kyotobyte.cdmplus.update.Updater.onAppOpen(applicationContext, app.appScope)
    }

    override fun onDestroy() {
        // Browser tabs live with the activity; release their WebViews when it goes away.
        io.github.kyotobyte.cdmplus.ui.BrowserSession.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ only lets the focused app read the clipboard.
        if (hasFocus) checkClipboard()
    }

    private fun checkClipboard() {
        if (!app.settings.value.clipboardDetect) return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        val url = extractUrls(text).firstOrNull() ?: return
        if (url == app.settings.lastClipboard() || app.core.items.value.any { it.url == url }) return
        clipboardUrl.value = url
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // The "CDM+ Browser" home-screen icon (an alias of this activity): straight into the browser, fresh tab.
        if (intent.action == Intent.ACTION_MAIN && intent.component?.className?.endsWith(".BrowserLauncher") == true) {
            state.tab = io.github.kyotobyte.cdmplus.ui.Tab.BROWSER
            state.openInBrowser = io.github.kyotobyte.cdmplus.ui.NEW_TAB
            return
        }
        when (intent.getStringExtra(EXTRA_TAB)) {
            "vpn" -> state.tab = io.github.kyotobyte.cdmplus.ui.Tab.VPN
        }
        if (intent.getBooleanExtra(io.github.kyotobyte.cdmplus.update.Updater.EXTRA_UPDATE, false)) {
            intent.removeExtra(io.github.kyotobyte.cdmplus.update.Updater.EXTRA_UPDATE)
            state.tab = io.github.kyotobyte.cdmplus.ui.Tab.DOWNLOADS
            io.github.kyotobyte.cdmplus.update.Updater.showDialog.value = true
        }
        if (intent.getBooleanExtra(io.github.kyotobyte.cdmplus.update.Updater.EXTRA_NOTICE, false)) {
            intent.removeExtra(io.github.kyotobyte.cdmplus.update.Updater.EXTRA_NOTICE)
            state.tab = io.github.kyotobyte.cdmplus.ui.Tab.DOWNLOADS
        }
        if (intent.getBooleanExtra(EXTRA_VPN_ON, false)) {
            // Only CDM+'s own tile may switch 1.1.1.1 on: it carries a private per-install token.
            val ok = intent.getStringExtra(EXTRA_VPN_TOKEN) == vpnToken(this)
            intent.removeExtra(EXTRA_VPN_ON); intent.removeExtra(EXTRA_VPN_TOKEN)
            if (ok) toggleVpn(true)
        }
        if (intent.data?.scheme == "cdmplus") { // home-screen shortcuts
            when (intent.data?.host) {
                "add" -> state.pendingAdd = ""
                "browser" -> state.tab = io.github.kyotobyte.cdmplus.ui.Tab.BROWSER
                "downloads" -> state.tab = io.github.kyotobyte.cdmplus.ui.Tab.DOWNLOADS
                "vpn" -> state.tab = io.github.kyotobyte.cdmplus.ui.Tab.VPN
            }
            return
        }
        // A .torrent file opened from a file manager / browser download
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && (data.scheme == "content" || data.scheme == "file") &&
            (intent.type == "application/x-bittorrent" || data.toString().endsWith(".torrent", true))) {
            runCatching {
                val f = java.io.File(cacheDir, "t" + System.currentTimeMillis() + ".torrent")
                contentResolver.openInputStream(data)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                state.pendingAdd = android.net.Uri.fromFile(f).toString()
            }.onFailure { state.toast = "Couldn't open that torrent file" }
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val urls = extractUrls(text)
        if (urls.isNotEmpty()) state.pendingAdd = urls.joinToString("\n")
        else Toast.makeText(this, "No link found in what was shared", Toast.LENGTH_SHORT).show()
    }

    private fun askPermissions() {
        val want = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) permLauncher.launch(want.toTypedArray())
    }

    private fun mimeOf(item: DownloadItem): String =
        item.mimeType ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.fileName.substringAfterLast('.', "").lowercase()) ?: "*/*"

    private fun openItem(item: DownloadItem) {
        val uri = item.savedUri?.let(Uri::parse) ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(Intent.createChooser(i, "Open with")) }.onFailure {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: DownloadItem) {
        val uri = item.savedUri?.let(Uri::parse) ?: return
        val i = Intent(Intent.ACTION_SEND).setType(mimeOf(item)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, "Share")) }.onFailure { state.toast = "No app can share this file" }
    }

    companion object {
        const val EXTRA_TAB = "cdm_tab"
        const val EXTRA_VPN_ON = "cdm_vpn_on"
        const val EXTRA_VPN_TOKEN = "cdm_vpn_token"
        private const val STATE_TAB = "cdm_state_tab"

        /** Random secret created on first use, kept in private prefs; other apps can't know it. */
        fun vpnToken(ctx: android.content.Context): String {
            val p = ctx.getSharedPreferences("cdm_private", android.content.Context.MODE_PRIVATE)
            p.getString("vpn_token", null)?.let { return it }
            val b = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
            val tok = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            p.edit().putString("vpn_token", tok).apply()
            return tok
        }
    }
}
