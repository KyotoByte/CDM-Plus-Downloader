package io.github.kyotobyte.cdmplus.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kyotobyte.cdmplus.data.WebEntry

data class CaughtDownload(val url: String, val fileName: String, val mime: String?, val size: Long, val headers: Map<String, String>)

/** Normalises what the user typed into a URL, or a search with the chosen engine. */
fun toUrl(input: String, searchTemplate: String = SearchEngine.GOOGLE.template): String {
    val t = input.trim()
    if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:")) return t
    if (!t.contains(' ') && t.contains('.') && !t.endsWith('.')) return "https://$t"
    return searchTemplate.replace("%s", java.net.URLEncoder.encode(t, "UTF-8"))
}

var HOME_URL = SearchEngine.GOOGLE.home
const val MAX_TABS = 20
/** Live WebViews kept in memory; older background tabs are released and reload when opened. */
const val MAX_LIVE_TABS = 6

class BrowserTab(val id: Long, url: String, title: String, val incognito: Boolean) {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var desktop by mutableStateOf(false)
    val media = mutableStateListOf<String>()
    var webView: WebView? = null
    var lastUsed = System.nanoTime()
    /** Host of the page now loading/shown; read off the UI thread by the ad-block. */
    @Volatile var pageHost: String? = runCatching { java.net.URI(url).host }.getOrNull()
    /** Main-frame load error shown over the page (net error code + hint), null when fine. */
    var error by mutableStateOf<String?>(null)
    /** Bumped to force the WebView to be re-attached / re-created (after a renderer crash). */
    var gen by mutableIntStateOf(0)
    /** The YouTube ad-strip script registered at document start (removed when ad-block is off). */
    var ytScript: androidx.webkit.ScriptHandler? = null
}

/** Registers / removes the YouTube ad-strip script on one tab's WebView. */
fun setYouTubeAdStrip(t: BrowserTab, wv: WebView, on: Boolean): Boolean {
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return false
    if (on && t.ytScript == null) t.ytScript = runCatching {
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, WebPolicy.YT_DOCSTART_JS, setOf("https://*.youtube.com", "https://youtube.com"))
    }.getOrNull()
    if (!on) { t.ytScript?.remove(); t.ytScript = null }
    return true
}

class SslPrompt(val tab: BrowserTab, val handler: android.webkit.SslErrorHandler, val reason: String, val url: String)
class GeoPrompt(val origin: String, val callback: android.webkit.GeolocationPermissions.Callback)

/**
 * UI state + callbacks the WebViews talk to. Lives with the tabs (not the composition), so tabs
 * survive leaving the Browser screen and their callbacks never point at a dead composition.
 */
class BrowserUi {
    var address by mutableStateOf("")
    var fillFor by mutableStateOf<String?>(null)
    var savePrompt by mutableStateOf<Triple<String, String, String>?>(null)
    var linkMenu by mutableStateOf<String?>(null)
    var sslPrompt by mutableStateOf<SslPrompt?>(null)
    var geoPrompt by mutableStateOf<GeoPrompt?>(null)
    @Volatile var adBlock: Boolean = true
    var onCaught: (CaughtDownload) -> Unit = {}
    var onToast: (String) -> Unit = {}
    var store: io.github.kyotobyte.cdmplus.data.BrowserStore? = null
    var vault: VaultUi? = null
    var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    var launchChooser: (android.content.Intent) -> Boolean = { false }
    var requestLocation: ((Boolean) -> Unit) -> Unit = { it(false) }
    var mobileUa: String? = null
}

/** Process-wide browser session: tabs and their WebViews stay alive while the app's activity lives. */
object BrowserSession {
    var holder: BrowserTabs? = null
    private var owner: Context? = null

    fun obtain(ctx: Context, create: () -> BrowserTabs): BrowserTabs {
        val h = holder
        if (h != null && owner === ctx) return h
        h?.destroyAll() // activity was recreated: old WebViews belong to the old one
        owner = ctx
        return create().also { holder = it }
    }

    fun destroy() { holder?.destroyAll(); holder = null; owner = null }
}

/** Tab list + WebView lifecycle for the in-app browser. */
class BrowserTabs {
    val ui = BrowserUi()
    val tabs = mutableStateListOf<BrowserTab>()
    var active by mutableIntStateOf(0)
    private var nextId = 1L
    val current: BrowserTab? get() = tabs.getOrNull(active)

    fun open(url: String = HOME_URL, incognito: Boolean = false, background: Boolean = false, title: String = ""): BrowserTab {
        if (tabs.size >= MAX_TABS) {
            // Cap: drop the least recently used tab that isn't the current one.
            val victim = tabs.filterIndexed { i, _ -> i != active }.minByOrNull { it.lastUsed }
            victim?.let { close(tabs.indexOf(it)) }
        }
        val t = BrowserTab(nextId++, url, title, incognito)
        tabs.add(t)
        if (!background) select(tabs.size - 1)
        return t
    }

    fun select(i: Int) {
        if (i !in tabs.indices) return
        current?.webView?.onPause()
        active = i
        val t = tabs[i]
        t.lastUsed = System.nanoTime()
        t.webView?.onResume()
        trim()
    }

    fun close(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        destroy(t)
        if (t.incognito && tabs.none { it.incognito }) IncognitoJar.wipe()
        if (tabs.isEmpty()) { active = 0; open() } else {
            if (i < active) active -= 1
            active = active.coerceIn(0, tabs.size - 1)
            select(active)
        }
    }

    /** Releases WebViews of background tabs beyond the live cap (they reload from their URL later). */
    fun trim() {
        val live = tabs.filter { it.webView != null }.sortedByDescending { it.lastUsed }
        live.drop(MAX_LIVE_TABS).forEach { if (it !== current) destroy(it) }
    }

    fun destroy(t: BrowserTab) {
        t.webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            if (t.incognito) { wv.clearHistory(); wv.clearCache(true); wv.clearFormData() }
            wv.destroy()
        }
        t.webView = null
        t.ytScript = null
    }

    fun destroyAll() { tabs.forEach { destroy(it) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    incognito: Boolean,
    onToggleIncognito: () -> Unit,
    onCaught: (CaughtDownload) -> Unit,
    onComingSoon: (String) -> Unit,
    store: io.github.kyotobyte.cdmplus.data.BrowserStore? = null,
    adBlock: Boolean = true,
    onBatch: (List<String>) -> Unit = {},
    onExit: () -> Unit = {},
    searchTemplate: String = SearchEngine.GOOGLE.template,
    homeUrl: String = SearchEngine.GOOGLE.home,
    onGrab: (url: String, html: String?) -> Unit = { _, _ -> },
    onCrawl: (String) -> Unit = {},
    vault: VaultUi? = null,
    onToast: (String) -> Unit = {},
    openUrl: String? = null,
    onOpened: () -> Unit = {},
) {
    HOME_URL = homeUrl
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val bookmarks by (store?.bookmarks ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val history by (store?.history ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val holder = BrowserSession.obtain(ctx) {
        BrowserTabs().apply {
            val (saved, act) = store?.loadTabs() ?: (emptyList<WebEntry>() to 0)
            saved.forEach { open(it.url, background = true, title = it.title) }
            if (tabs.isEmpty()) open(incognito = incognito) else select(act.coerceIn(0, tabs.size - 1))
        }
    }
    androidx.compose.runtime.LaunchedEffect(openUrl) { if (openUrl != null) { holder.open(openUrl, incognito = false); onOpened() } }
    val ui = holder.ui
    // Callbacks follow the newest composition; the WebViews only ever call through holder.ui.
    ui.onCaught = onCaught; ui.onToast = onToast; ui.store = store; ui.vault = vault; ui.adBlock = adBlock
    val chooser = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = ui.fileCallback; ui.fileCallback = null
        val data = res.data
        val uris: Array<android.net.Uri>? = if (res.resultCode != android.app.Activity.RESULT_OK) null else {
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 0) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else android.webkit.WebChromeClient.FileChooserParams.parseResult(res.resultCode, data)
        }
        cb?.onReceiveValue(uris)
    }
    ui.launchChooser = { intent -> runCatching { chooser.launch(intent) }.isSuccess }
    var locationDone by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val locPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        locationDone?.invoke(r.values.any { it }); locationDone = null
    }
    ui.requestLocation = { done ->
        val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) done(true) else { locationDone = done; locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }
    if (ui.mobileUa == null) ui.mobileUa = runCatching { WebPolicy.chromeLikeUa(WebSettings.getDefaultUserAgent(ctx)) }.getOrNull()
    androidx.compose.runtime.LaunchedEffect(adBlock) {
        val on = adBlock && !io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor("youtube.com")
        holder.tabs.forEach { bt -> bt.webView?.let { setYouTubeAdStrip(bt, it, on) } }
    }
    val tab = holder.current ?: return
    androidx.compose.runtime.LaunchedEffect(tab.id) { ui.address = tab.url }
    var address by ui::address
    var showLists by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var grabbed by remember { mutableStateOf<List<String>?>(null) }
    var linkMenu by ui::linkMenu
    var fillFor by ui::fillFor
    var savePrompt by ui::savePrompt
    var reader by remember { mutableStateOf<Pair<String, Reader.Article>?>(null) }

    fun caughtHeaders(wv: WebView?, url: String, ua: String? = null) = buildMap {
        (ua ?: wv?.settings?.userAgentString)?.let { put("User-Agent", it) }
        IncognitoJar.cookies(holder.current?.incognito == true).getCookie(url)?.let { put("Cookie", it) }
        wv?.url?.let { put("Referer", it) }
    }

    fun makeWebView(t: BrowserTab, load: Boolean = true): WebView = WebView(ctx).apply {
        // Everything below talks to holder.ui / t only, never to this composition's locals.
        val ui = holder.ui
        if (t.incognito) IncognitoJar.attach(this) // own cookie jar where WebView supports it
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // many sites crash without localStorage, incognito too
        @Suppress("DEPRECATION") settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true // popups still need a tap: see onCreateWindow
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = if (t.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        settings.offscreenPreRaster = true
        if (android.os.Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        ui.mobileUa?.let { settings.userAgentString = it }
        if (t.desktop) MediaSniffer.applyDesktop(this, true, ui.mobileUa)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) // Google/Facebook login, payments, comments
        val docStart = setYouTubeAdStrip(t, this, ui.adBlock && !io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor("youtube.com"))
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.substringBefore('#') != t.url.substringBefore('#')) t.media.clear()
                t.url = url
                t.pageHost = android.net.Uri.parse(url).host
                if (t.incognito) IncognitoJar.note(url)
                t.error = null
                if (holder.current === t) ui.address = url
                // Older WebViews without document-start scripts: inject as early as we can.
                if (!docStart && ui.adBlock && WebPolicy.isYouTube(t.pageHost) && !io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor(t.pageHost)) view.evaluateJavascript(WebPolicy.YT_DOCSTART_JS, null)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.canBack = view.canGoBack(); t.url = url
                t.pageHost = android.net.Uri.parse(url).host
                if (holder.current === t) ui.address = url
                if (ui.adBlock && WebPolicy.isYouTube(t.pageHost) && !io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor(t.pageHost)) view.evaluateJavascript(WebPolicy.YT_ADSKIP_JS, null)
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (ui.adBlock && WebPolicy.isYouTube(t.pageHost) && !io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor(t.pageHost)) view.evaluateJavascript(WebPolicy.YT_ADSKIP_JS, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                t.title = view.title ?: ""
                if (ui.adBlock && WebPolicy.isYouTube(t.pageHost) && !io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor(t.pageHost)) view.evaluateJavascript(WebPolicy.YT_ADSKIP_JS, null)
                if (!t.incognito && url.startsWith("http")) ui.store?.addHistory(url, t.title)
                if (t.incognito && url.startsWith("http")) IncognitoJar.note(url)
                // Password manager: offer to fill saved logins, and to save new ones (never in incognito).
                val v = ui.vault
                if (v != null && !t.incognito && url.startsWith("http")) {
                    if (v.enabled()) view.evaluateJavascript(io.github.kyotobyte.cdmplus.data.PasswordVault.CAPTURE_SCRIPT, null)
                    view.evaluateJavascript("!!document.querySelector('input[type=password]')") { has ->
                        if (has == "true" && holder.current === t && v.hasLogin(url)) ui.fillFor = url
                    }
                } else if (holder.current === t) ui.fillFor = null
            }
            // Observe-only: the ad-block answers matched ad requests with an empty body; everything
            // else returns null so the WebView fetches it itself (cookies, POST, CORS, redirects intact).
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                if (ui.adBlock && io.github.kyotobyte.cdmplus.data.AdBlock.shouldBlock(request.url, t.pageHost, request.isForMainFrame)) {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                val u = request.url.toString()
                if (MediaSniffer.isMedia(u)) view.post { if (u !in t.media) t.media.add(0, u) }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.substringBefore('?').endsWith(".m3u8", true)) {
                    ui.onCaught(CaughtDownload(u, URLUtil.guessFileName(u, null, null).replace(".m3u8", ".ts"), "application/vnd.apple.mpegurl", -1, mapOf("Referer" to (view.url ?: ""))))
                    return true
                }
                return when (val r = WebPolicy.route(u)) {
                    WebPolicy.Route.Web -> false
                    WebPolicy.Route.Block -> true
                    is WebPolicy.Route.AppIntent -> {
                        val opened = runCatching {
                            val i = android.content.Intent.parseUri(u, android.content.Intent.URI_INTENT_SCHEME)
                            i.addCategory(android.content.Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
                            ctx.startActivity(i)
                        }.isSuccess
                        if (!opened) when {
                            r.fallback != null -> view.loadUrl(r.fallback)
                            r.pkg != null -> view.loadUrl(WebPolicy.storeUrl(r.pkg))
                            else -> ui.onToast("No app installed for this link")
                        }
                        true
                    }
                    is WebPolicy.Route.External -> {
                        val opened = runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
                        }.isSuccess
                        if (!opened) {
                            if (r.scheme == "market") view.loadUrl("https://play.google.com/store/apps/" + u.substringAfter("market://"))
                            else ui.onToast("No app installed for ${r.scheme}: links")
                        }
                        true
                    }
                }
            }
            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                val code = error.errorCode
                if (code == ERROR_UNSUPPORTED_SCHEME) return // handled as an app link above
                t.error = "${error.description} (code $code)\n\n${WebPolicy.errorHint(code)}"
            }
            override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {}
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                val reason = when (error.primaryError) {
                    android.net.http.SslError.SSL_EXPIRED -> "The site's certificate has expired."
                    android.net.http.SslError.SSL_IDMISMATCH -> "The certificate is for a different site."
                    android.net.http.SslError.SSL_NOTYETVALID -> "The certificate isn't valid yet. Check your phone's date and time."
                    android.net.http.SslError.SSL_UNTRUSTED -> "The certificate isn't trusted."
                    android.net.http.SslError.SSL_DATE_INVALID -> "The certificate date is invalid. Check your phone's date and time."
                    else -> "The secure connection has a problem."
                }
                ui.sslPrompt?.handler?.cancel()
                ui.sslPrompt = SslPrompt(t, handler, reason, error.url ?: (view.url ?: ""))
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // A crashed page must not take the whole app down: drop this WebView, offer a reload.
                (view.parent as? ViewGroup)?.removeView(view)
                if (t.webView === view) { t.webView = null; t.ytScript = null }
                runCatching { view.destroy() }
                t.error = "This page crashed (out of memory or a renderer problem).\n\nTap Retry to load it again."
                return true
            }
        }
        if (!t.incognito) addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun offer(host: String, user: String, pass: String) {
                post {
                    val page = url ?: return@post
                    // Only the page's own frame: an ad iframe reports its own host and is ignored.
                    if (!host.equals(android.net.Uri.parse(page).host ?: "", ignoreCase = true)) return@post
                    val v = ui.vault ?: return@post
                    if (pass.isNotEmpty() && v.enabled() && !v.isSame(page, user, pass)) ui.savePrompt = Triple(page, user, pass)
                }
            }
        }, "CdmVault")
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                // DRM video (Widevine) asks for protected media: CDM+ won't help download it.
                if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    post { ui.onToast("Protected content, can't be downloaded") }
                }
                request.deny()
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: "" }
            // target=_blank links and window.open: a new tab, like Chrome. Popups without a tap are blocked.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val nt = holder.open("about:blank", incognito = t.incognito)
                val wv = makeWebView(nt, load = false)
                nt.webView = wv
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = wv
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                val i = holder.tabs.indexOfFirst { it.webView === window }
                if (i >= 0) holder.close(i)
            }
            override fun onShowFileChooser(webView: WebView, filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>, fileChooserParams: FileChooserParams): Boolean {
                ui.fileCallback?.onReceiveValue(null)
                ui.fileCallback = filePathCallback
                val intent = runCatching { fileChooserParams.createIntent() }.getOrNull()
                    ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                val ok = ui.launchChooser(intent) ||
                    ui.launchChooser(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"))
                if (!ok) { ui.fileCallback = null; filePathCallback.onReceiveValue(null) }
                return true
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                if (t.incognito) { callback.invoke(origin, false, false); return }
                ui.geoPrompt = GeoPrompt(origin, callback)
            }
        }
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val guess = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val name = io.github.kyotobyte.cdmplus.engine.FileNamer.improve(guess, contentDisposition, url, mimetype)
            ui.onCaught(CaughtDownload(url, name, mimetype, contentLength, caughtHeaders(this, url, userAgent)))
        }
        // Long-press a link: open in a new tab, incognito tab, or download it.
        setOnLongClickListener {
            val hr = hitTestResult
            val link = hr.extra
            if (link != null && (hr.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hr.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE || hr.type == WebView.HitTestResult.IMAGE_TYPE)) {
                ui.linkMenu = link; true
            } else false
        }
        if (load) loadUrl(t.url)
    }

    // Back: page history first, then close this tab, then leave the browser (never exits the app).
    BackHandler(enabled = true) {
        val wv = tab.webView
        when {
            wv != null && wv.canGoBack() -> wv.goBack()
            holder.tabs.size > 1 -> holder.close(holder.active)
            else -> onExit()
        }
    }

    // Leaving the Browser screen keeps every tab loaded (no reload when you come back); tabs are
    // saved for the next app start and the shown page is paused to save battery.
    DisposableEffect(Unit) {
        holder.current?.webView?.onResume()
        onDispose {
            store?.saveTabs(holder.tabs.filter { !it.incognito }.map { WebEntry(it.url, it.title) }, holder.tabs.filter { !it.incognito }.indexOf(holder.current).coerceAtLeast(0))
            CookieManager.getInstance().flush()
            holder.current?.webView?.onPause()
        }
    }

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PixelIconButton(PxIcons.Back, { tab.webView?.goBack() }, size = 34.dp)
            Box(Modifier.weight(1f).height(34.dp).pixelBorder(if (tab.incognito) Px.Amber else Px.Line).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    address, { address = it }, singleLine = true, textStyle = Type.Body, cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { tab.url = toUrl(address, searchTemplate); tab.webView?.loadUrl(tab.url) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PixelIconButton(PxIcons.Reload, { tab.webView?.reload() }, size = 34.dp)
            PixelIconButton(PxIcons.Star, { store?.toggleBookmark(tab.url, tab.title) }, if (bookmarks.any { it.url == tab.url }) Px.Amber else Px.Dim, size = 34.dp)
            PixelIconButton(PxIcons.List, { showLists = true }, size = 34.dp)
            // Tab counter button opens the switcher
            Box(Modifier.size(34.dp).pixelBorder(if (tab.incognito) Px.Amber else Px.Text).clickable { showTabs = true }, contentAlignment = Alignment.Center) {
                Text(holder.tabs.size.toString(), style = Type.Label.copy(color = if (tab.incognito) Px.Amber else Px.Text))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (tab.incognito) "INCOGNITO" else "Downloads are caught", style = Type.Small.copy(color = if (tab.incognito) Px.Amber else Px.Dim), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("MEDIA ${tab.media.size}", style = Type.Label.copy(color = if (tab.media.isEmpty()) Px.Dim else accent), modifier = Modifier.clickable { showMedia = true }.padding(5.dp))
            Text("LINKS", style = Type.Label.copy(color = Px.Dim), modifier = Modifier.clickable {
                tab.webView?.evaluateJavascript(MediaSniffer.GRAB_JS) { json -> grabbed = MediaSniffer.parseGrab(json, tab.webView?.url) }
            }.padding(5.dp))
            Text("DESK", style = Type.Label.copy(color = if (tab.desktop) accent else Px.Dim), modifier = Modifier.clickable {
                tab.desktop = !tab.desktop
                tab.webView?.let { wv -> MediaSniffer.applyDesktop(wv, tab.desktop, ui.mobileUa); wv.reload() }
            }.padding(5.dp))
            Text("PDF", style = Type.Label.copy(color = Px.Dim), modifier = Modifier.clickable { tab.webView?.let { MediaSniffer.savePdf(ctx, it) } }.padding(5.dp))
            if (adBlock) {
                var siteAds by remember(tab.id, tab.url) { mutableStateOf(!io.github.kyotobyte.cdmplus.data.AdBlock.isOffFor(tab.pageHost)) }
                Text(if (siteAds) "ADS✕" else "ADS", style = Type.Label.copy(color = if (siteAds) accent else Px.Amber), modifier = Modifier.clickable {
                    siteAds = io.github.kyotobyte.cdmplus.data.AdBlock.toggleSite(tab.pageHost)
                    if (WebPolicy.isYouTube(tab.pageHost)) holder.tabs.forEach { bt -> bt.webView?.let { setYouTubeAdStrip(bt, it, siteAds) } }
                    onToast(if (siteAds) "Ad-block on for this site" else "Ad-block off for this site")
                    tab.webView?.reload()
                }.padding(5.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val social = io.github.kyotobyte.cdmplus.grab.SocialGrabber.isSupported(tab.url) || io.github.kyotobyte.cdmplus.grab.MediaSiteGrabber.isSupported(tab.url) || io.github.kyotobyte.cdmplus.grab.MegaGrabber.isSupported(tab.url)
            Text("GRAB", style = Type.Label.copy(color = if (social) accent else Px.Dim), modifier = Modifier.clickable {
                val wv = tab.webView ?: return@clickable
                val u = wv.url ?: tab.url
                io.github.kyotobyte.cdmplus.grab.SniffedMedia.set(u, tab.media.toList())
                if (io.github.kyotobyte.cdmplus.grab.MediaSiteGrabber.isSupported(u) || io.github.kyotobyte.cdmplus.grab.MegaGrabber.isSupported(u)) onGrab(u, null)
                else wv.evaluateJavascript("document.documentElement.outerHTML") { js -> onGrab(u, runCatching { kotlinx.serialization.json.Json.decodeFromString<String>(js) }.getOrNull()) }
            }.padding(5.dp))
            Text("CRAWL", style = Type.Label.copy(color = Px.Dim), modifier = Modifier.clickable { onCrawl(tab.webView?.url ?: tab.url) }.padding(5.dp))
            Text("READ", style = Type.Label.copy(color = Px.Dim), modifier = Modifier.clickable {
                val wv = tab.webView ?: return@clickable
                val u = wv.url ?: tab.url
                wv.evaluateJavascript("document.documentElement.outerHTML") { js ->
                    val html = runCatching { kotlinx.serialization.json.Json.decodeFromString<String>(js) }.getOrNull() ?: ""
                    val a = runCatching { Reader.extract(u, html) }.getOrNull()
                    if (a == null) onToast("No article found on this page") else reader = u to a
                }
            }.padding(5.dp))
            Spacer(Modifier.weight(1f))
            if (fillFor != null && fillFor == (tab.webView?.url ?: tab.url)) Text("FILL LOGIN", style = Type.Label.copy(color = Px.Bg), modifier = Modifier.background(accent).clickable {
                val u = fillFor ?: return@clickable
                vault?.fill(u) { script -> tab.webView?.evaluateJavascript(script, null) }
            }.padding(horizontal = 8.dp, vertical = 5.dp))
        }
        if (tab.progress in 1..99) PixelProgress(tab.progress / 100f, accent, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
        else Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c -> FrameLayout(c) },
            update = { frame ->
                @Suppress("UNUSED_VARIABLE") val g = tab.gen // re-run after a crashed page is retried
                val wv = tab.webView ?: makeWebView(tab).also { tab.webView = it; holder.trim() }
                if (frame.childCount != 1 || frame.getChildAt(0) !== wv) {
                    frame.removeAllViews()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    wv.onResume()
                }
            },
        )
        tab.error?.let { err ->
            Column(Modifier.fillMaxSize().background(Px.Bg).padding(24.dp)) {
                Text("PAGE DIDN'T LOAD", style = Type.H2.copy(color = accent))
                Spacer(Modifier.height(8.dp))
                Text(tab.url, style = Type.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Text(err, style = Type.Body)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Retry", {
                        tab.error = null
                        val wv = tab.webView
                        if (wv == null) tab.gen++ else if (wv.url.isNullOrBlank() || wv.url == "about:blank") wv.loadUrl(tab.url) else wv.reload()
                    }, modifier = Modifier.weight(1f))
                    PixelButton("Back", { tab.error = null; tab.webView?.let { if (it.canGoBack()) it.goBack() } }, filled = false, modifier = Modifier.weight(1f))
                }
            }
        }
        }
    }

    ui.sslPrompt?.let { p ->
        PixelDialog("Not secure", onDismiss = { p.handler.cancel(); ui.sslPrompt = null }) {
            Text(android.net.Uri.parse(p.url).host ?: p.url, style = Type.BodyStrong)
            Spacer(Modifier.height(6.dp))
            Text(p.reason + "\n\nSomeone could be reading or changing this page. Only continue if you trust this network and site.", style = Type.Small)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Go back", { p.handler.cancel(); ui.sslPrompt = null; p.tab.error = p.reason + "\n\nThe page was stopped because the connection isn't secure." }, modifier = Modifier.weight(1f))
                PixelButton("Continue", { p.handler.proceed(); ui.sslPrompt = null }, filled = false, color = Px.Amber, modifier = Modifier.weight(1f))
            }
        }
    }
    ui.geoPrompt?.let { p ->
        PixelDialog("Location", onDismiss = { p.callback.invoke(p.origin, false, false); ui.geoPrompt = null }) {
            Text("${android.net.Uri.parse(p.origin).host ?: p.origin} wants to use your location.", style = Type.Body)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Block", { p.callback.invoke(p.origin, false, false); ui.geoPrompt = null }, filled = false, modifier = Modifier.weight(1f))
                PixelButton("Allow", { ui.geoPrompt = null; ui.requestLocation { ok -> p.callback.invoke(p.origin, ok, false) } }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showTabs) TabSwitcher(holder, onClose = { showTabs = false })
    savePrompt?.let { (page, user, pass) ->
        PixelDialog("Save password?", onDismiss = { savePrompt = null }) {
            Text("${io.github.kyotobyte.cdmplus.data.PasswordVault.siteOf(page)}\n${user.ifBlank { "(no username)" }}", style = Type.Body)
            Spacer(Modifier.height(6.dp))
            Text("Encrypted on this phone with the Android Keystore. Fingerprint / screen lock needed to fill it.", style = Type.Small)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Not now", { savePrompt = null }, filled = false, modifier = Modifier.weight(1f))
                PixelButton("Save", { vault?.save(page, user, pass); savePrompt = null; onToast("Password saved") }, modifier = Modifier.weight(1f))
            }
        }
    }
    reader?.let { (u, a) -> ReaderView(u, a) { reader = null } }

    linkMenu?.let { link ->
        PixelDialog("Link", onDismiss = { linkMenu = null }) {
            Text(link, style = Type.Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            PixelButton("Open in new tab", { holder.open(link, incognito = tab.incognito, background = true); linkMenu = null }, filled = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PixelButton("Open in incognito tab", { holder.open(link, incognito = true); linkMenu = null }, filled = false, color = Px.Amber, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PixelButton("Download with CDM+", { onCaught(CaughtDownload(link, URLUtil.guessFileName(link, null, null), null, -1, caughtHeaders(tab.webView, link))); linkMenu = null }, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showMedia) {
        PixelDialog("Media on this page", onDismiss = { showMedia = false }) {
            if (tab.media.isEmpty()) Text("Nothing detected yet. Play the video or audio on the page, then open this again. (Not for YouTube or DRM streams.)", style = Type.Small)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                items(tab.media.size) { i ->
                    val u = tab.media[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(MediaSniffer.label(u), style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(u, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PixelIconButton(PxIcons.Download, {
                            val name = URLUtil.guessFileName(u, null, null).let { if (it.endsWith(".m3u8", true)) it.dropLast(5) + ".ts" else it }
                            onCaught(CaughtDownload(u, name, null, -1, caughtHeaders(tab.webView, u))); showMedia = false
                        }, accent, size = 32.dp)
                    }
                }
            }
        }
    }

    grabbed?.let { links ->
        val picked = remember(links) { mutableStateListOf<String>().apply { addAll(links.filter { MediaSniffer.looksLikeFile(it) }) } }
        var filter by remember(links) { mutableStateOf("") }
        PixelDialog("Links on this page (${links.size})", onDismiss = { grabbed = null }) {
            PixelField(filter, { filter = it }, "filter, e.g. .pdf or .jpg")
            Spacer(Modifier.height(8.dp))
            val shown = links.filter { filter.isBlank() || it.contains(filter.trim(), true) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(300.dp)) {
                items(shown.size) { i ->
                    val u = shown[i]; val on = u in picked
                    Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(u) else picked.add(u) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(14.dp).height(14.dp).pixelBorder(if (on) accent else Px.Line).padding(3.dp).background(if (on) accent else Px.Panel))
                        Spacer(Modifier.width(10.dp))
                        Text(u.substringAfter("://"), style = Type.Small.copy(color = if (on) Px.Text else Px.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("All", { picked.clear(); picked.addAll(shown) }, filled = false, modifier = Modifier.weight(1f))
                PixelButton("Download ${picked.size}", { if (picked.isNotEmpty()) { onBatch(picked.toList()); grabbed = null } }, modifier = Modifier.weight(2f))
            }
        }
    }

    if (showLists) {
        var which by remember { mutableIntStateOf(0) }
        PixelDialog("Saved pages", onDismiss = { showLists = false }) {
            PixelTabs(listOf("Bookmarks", "History"), which, { which = it })
            Spacer(Modifier.height(10.dp))
            val list = if (which == 0) bookmarks else history
            if (list.isEmpty()) Text(if (which == 0) "No bookmarks yet. Tap the star." else "No history.", style = Type.Small)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                items(list.size) { i ->
                    val e = list[i]
                    Column(Modifier.fillMaxWidth().clickable { tab.url = e.url; tab.webView?.loadUrl(e.url); showLists = false }.padding(vertical = 8.dp)) {
                        Text(e.title, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.url, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (which == 1 && history.isNotEmpty()) { Spacer(Modifier.height(8.dp)); PixelButton("Clear history", { store?.clearHistory() }, filled = false) }
        }
    }
}

/** Pixel grid of open tabs: big initial, title, site; close, new tab, new incognito tab. */
@Composable
fun TabSwitcher(holder: BrowserTabs, onClose: () -> Unit) {
    val accent = LocalAccent.current
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Px.Bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TABS ${holder.tabs.size}/$MAX_TABS", style = Type.H1.copy(color = accent), modifier = Modifier.weight(1f))
            PixelIconButton(PxIcons.Close, onClose)
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(holder.tabs.size) { i ->
                val t = holder.tabs[i]
                val sel = i == holder.active
                val host = t.url.substringAfter("://").substringBefore('/').removePrefix("www.")
                val border = if (sel) accent else if (t.incognito) Px.Amber else Px.Line
                Column(Modifier.fillMaxWidth().background(Px.Panel).pixelBorder(border).clickable { holder.select(i); onClose() }.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).background(if (t.incognito) Px.Amber else border.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                            Text((host.firstOrNull() ?: '?').uppercaseChar().toString(), style = Type.H1.copy(color = if (t.incognito) Px.Bg else Px.Text))
                        }
                        Spacer(Modifier.weight(1f))
                        PixelIconButton(PxIcons.Close, { holder.close(i) }, Px.Dim, size = 28.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t.title.ifBlank { host.ifBlank { "New tab" } }, style = Type.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (t.incognito) "incognito · $host" else host, style = Type.Small.copy(color = if (t.incognito) Px.Amber else Px.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("+ Tab", { holder.open(); onClose() }, modifier = Modifier.weight(1f))
            PixelButton("+ Incognito", { holder.open(incognito = true); onClose() }, filled = false, color = Px.Amber, modifier = Modifier.weight(1f))
        }
    }
}
