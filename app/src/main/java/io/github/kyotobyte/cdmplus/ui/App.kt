package io.github.kyotobyte.cdmplus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kyotobyte.cdmplus.data.AppSettings
import io.github.kyotobyte.cdmplus.engine.DownloadItem
import kotlinx.coroutines.delay

/** The original splash: pixel logo + the developer link. */
@Composable
fun SplashContent(progress: Float = 0.7f) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().background(Px.Bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        PixelIcon(AppGlyph, accent, 96.dp)
        Spacer(Modifier.height(28.dp))
        Text("CDM+", style = Type.H1.copy(color = Px.Text, fontSize = Type.H1.fontSize * 1.6f))
        Spacer(Modifier.height(10.dp))
        Text("DOWNLOADER", style = Type.Label.copy(color = Px.Dim))
        Spacer(Modifier.height(36.dp))
        PixelProgress(progress, accent, Modifier.width(160.dp))
        Spacer(Modifier.height(64.dp))
        Text("github.com/KyotoByte", style = Type.Label.copy(color = accent), textAlign = TextAlign.Center)
    }
}

val AppGlyph = listOf(
    "......####......",
    "......####......",
    "......####......",
    "......####......",
    "......####......",
    "...##########...",
    "....########....",
    ".....######.....",
    "......####......",
    ".......##.......",
    "................",
    "..##........##..",
    "..##........##..",
    "..############..",
    "..############..",
)

enum class Tab(val label: String, val icon: List<String>) { DOWNLOADS("Downloads", PxIcons.Download), BROWSER("Browser", PxIcons.Globe), VPN("1.1.1.1", PxIcons.Shield), SETTINGS("Settings", PxIcons.Gear) }

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().background(Px.Panel).pixelBorder().navigationBarsPadding()) {
        Tab.entries.forEach { t ->
            val on = t == current
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PixelIcon(t.icon, if (on) accent else Px.Dim, 16.dp)
                Spacer(Modifier.height(6.dp))
                // One line on any width / font size: shrinks to fit instead of wrapping ("DOWNLOA/DS" on small phones).
                androidx.compose.foundation.text.BasicText(
                    t.label.uppercase(), style = Type.Label.copy(color = if (on) accent else Px.Dim, fontSize = Type.Label.fontSize * 0.85f),
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 2.dp),
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 5.sp, maxFontSize = Type.Label.fontSize * 0.85f),
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(24.dp).height(2.dp).background(if (on) accent else Px.Panel))
            }
        }
    }
}

class AppState {
    var pendingAdd by mutableStateOf<String?>(null)
    var pendingName by mutableStateOf<String?>(null)
    var pendingHeaders by mutableStateOf<Map<String, String>>(emptyMap())
    var pendingMime by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var tab by mutableStateOf(Tab.DOWNLOADS)
    /** Grabber sheet: link (+ page HTML when it came from the in-app browser). */
    var grab by mutableStateOf<Pair<String, String?>?>(null)
    var crawl by mutableStateOf<String?>(null)
    var torrentPick by mutableStateOf<String?>(null)
    var openInBrowser by mutableStateOf<String?>(null)
}

/** Build-4 features the root screen needs from the app (grabbers, crawler, torrents, QR, passwords). */
class Extras(
    val grab: suspend (url: String, html: String?) -> io.github.kyotobyte.cdmplus.grab.GrabResult,
    val onGrabDownload: (List<GrabRequest>) -> Unit,
    val crawl: suspend (String, io.github.kyotobyte.cdmplus.engine.LinkCrawler.Options, (Int, Int) -> Unit) -> List<io.github.kyotobyte.cdmplus.engine.LinkCrawler.Found>,
    val torrentFiles: suspend (id: String, url: String) -> List<io.github.kyotobyte.cdmplus.engine.TorrentDownloader.TFile>,
    val addTorrent: (id: String, url: String, files: List<Int>?) -> Unit,
    val scanQr: () -> Unit,
    val vault: VaultUi?,
    val passwordsContent: @Composable () -> Unit,
)

fun isGrabbable(url: String) = io.github.kyotobyte.cdmplus.grab.SocialGrabber.isSupported(url) || io.github.kyotobyte.cdmplus.grab.MediaSiteGrabber.isSupported(url) || io.github.kyotobyte.cdmplus.grab.MegaGrabber.isSupported(url)

@Composable
fun CdmRoot(
    state: AppState,
    items: List<DownloadItem>,
    speeds: Map<String, Long>,
    settings: AppSettings,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    actions: DownloadActions,
    onAdd: (AddRequest, Map<String, String>, String?) -> Unit,
    clipboardUrl: String?,
    onClipboardDismiss: () -> Unit,
    showSplash: Boolean = true,
    history: List<Long> = emptyList(),
    browserStore: io.github.kyotobyte.cdmplus.data.BrowserStore? = null,
    onPickFolder: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    vpnContent: @Composable () -> Unit = {},
    onFloating: ((Boolean) -> Unit)? = null,
    onExportSettings: () -> Unit = {},
    onImportSettings: () -> Unit = {},
    extras: Extras? = null,
) {
    // Instagram / TikTok / YouTube links go to the grabber; torrents to the file picker.
    LaunchedEffect(state.pendingAdd) {
        val raw = state.pendingAdd?.trim() ?: return@LaunchedEffect
        if (extras == null || raw.isEmpty() || state.pendingName != null) return@LaunchedEffect
        // Shared text can be "Title\nhttps://youtu.be/ID?si=..." — use the link inside it.
        val links = extractUrls(raw)
        val p = if (links.size == 1) links[0] else raw
        if (links.size <= 1 && isGrabbable(p)) { state.grab = p to null; state.pendingAdd = null }
        else if (p.startsWith("magnet:", true) || p.substringBefore('?').endsWith(".torrent", true)) { state.torrentPick = p; state.pendingAdd = null }
    }
    var splash by rememberSaveable { mutableStateOf(showSplash) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(150); tick++ } }
    LaunchedEffect(Unit) { delay(1400); splash = false }
    LaunchedEffect(state.toast) { if (state.toast != null) { delay(2200); state.toast = null } }

    // Back on other tabs returns to Downloads; on Downloads it needs a second press to exit.
    // Screens register their own handlers after this one, so the browser and dialogs go first.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var lastBack by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = !splash) {
        if (state.tab != Tab.DOWNLOADS) state.tab = Tab.DOWNLOADS
        else {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2000) activity?.finish() else { lastBack = now; state.toast = "Press back again to exit" }
        }
    }

    Box(Modifier.fillMaxSize().background(Px.Bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).statusBarsPadding().imePadding()) {
            Box(Modifier.weight(1f)) {
                when (state.tab) {
                    Tab.DOWNLOADS -> DownloadsScreen(
                        items, speeds, actions, onAdd = { state.pendingAdd = "" },
                        clipboardUrl = clipboardUrl,
                        onClipboardUse = { state.pendingAdd = clipboardUrl; onClipboardDismiss() },
                        onClipboardDismiss = onClipboardDismiss, tick = tick, history = history,
                        onScanQr = extras?.scanQr,
                    )
                    Tab.BROWSER -> BrowserScreen(
                        incognito = settings.incognito,
                        onToggleIncognito = { onSettings { it.copy(incognito = !it.incognito) } },
                        onCaught = { c ->
                            state.pendingAdd = c.url; state.pendingName = c.fileName; state.pendingHeaders = c.headers; state.pendingMime = c.mime
                        },
                        onComingSoon = { state.toast = "$it: coming in next update" },
                        store = browserStore, adBlock = settings.adBlock,
                        onBatch = { urls -> state.pendingAdd = urls.joinToString("\n") },
                        onExit = { state.tab = Tab.DOWNLOADS },
                        searchTemplate = SearchEngine.resolve(settings.searchEngine, settings.customSearch).first,
                        homeUrl = SearchEngine.resolve(settings.searchEngine, settings.customSearch).second,
                        onGrab = { u, html -> state.grab = u to html },
                        onCrawl = { state.crawl = it },
                        vault = extras?.vault,
                        onToast = { state.toast = it },
                        openUrl = state.openInBrowser, onOpened = { state.openInBrowser = null },
                    )
                    Tab.VPN -> vpnContent()
                    Tab.SETTINGS -> SettingsScreen(settings, onSettings, { state.toast = "$it: coming in next update" }, onPickFolder, onExport, onImport, onFloating ?: { v -> onSettings { it.copy(floating = v) } }, onExportSettings, onImportSettings, passwords = extras?.passwordsContent)
                }
            }
            BottomBar(state.tab) { state.tab = it }
        }
        state.toast?.let { msg ->
            Box(Modifier.fillMaxSize().padding(bottom = 96.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
                Text(msg, style = Type.Label.copy(color = Px.Bg), modifier = Modifier.background(LocalAccent.current).padding(12.dp))
            }
        }
        AnimatedVisibility(splash, exit = fadeOut()) { SplashContent(((tick % 10) + 1) / 10f) }
    }

    state.pendingAdd?.let { initial ->
        val fromBrowser = state.pendingName != null
        AddDownloadDialog(
            initialText = initial, defaultThreads = settings.threads, suggestedName = state.pendingName,
            onDismiss = { state.pendingAdd = null; state.pendingName = null; state.pendingHeaders = emptyMap(); state.pendingMime = null },
            onConfirm = { req0 ->
                // Pasted Instagram / TikTok / YouTube links open the grabber instead of saving the web page.
                val grabbable = if (fromBrowser || extras == null) null else req0.urls.firstOrNull { isGrabbable(it) }
                val req = if (grabbable != null) req0.copy(urls = req0.urls.filterNot { isGrabbable(it) }) else req0
                if (req.urls.isNotEmpty()) onAdd(req, if (fromBrowser) state.pendingHeaders else emptyMap(), state.pendingMime)
                if (grabbable != null) {
                    state.pendingAdd = null; state.pendingName = null; state.pendingHeaders = emptyMap(); state.pendingMime = null
                    state.grab = grabbable to null
                    return@AddDownloadDialog
                }
                state.pendingAdd = null; state.pendingName = null; state.pendingHeaders = emptyMap(); state.pendingMime = null
                state.toast = if (req.scheduledAt != null) "Scheduled" else if (req.urls.size > 1) "${req.urls.size} downloads added" else "Download started"
                state.tab = Tab.DOWNLOADS
            },
        )
    }

    if (extras != null) {
        state.grab?.let { (u, html) ->
            GrabDialog(
                url = u,
                load = {
                    val sniffed = io.github.kyotobyte.cdmplus.grab.SniffedMedia.forPage(u)
                    if (io.github.kyotobyte.cdmplus.grab.MegaGrabber.isSupported(u)) extras.grab(u, null)
                    else if (html != null || sniffed.isNotEmpty()) io.github.kyotobyte.cdmplus.grab.SocialGrabber.fromPage(u, html, sniffed) { extras.grab(u, null) } else extras.grab(u, null)
                },
                resolve = { extras.grab(it, null) },
                onDownload = { reqs -> extras.onGrabDownload(reqs); if (reqs.isNotEmpty()) state.toast = if (reqs.size == 1) "Download started" else "${reqs.size} downloads added" },
                onOpenInBrowser = { link -> state.grab = null; state.openInBrowser = link; state.tab = Tab.BROWSER },
                onDismiss = { state.grab = null },
            )
        }
        state.crawl?.let { start ->
            CrawlDialog(start, settings.crawlDepth, settings.crawlSameSite, extras.crawl,
                onAdd = { urls -> urls.forEach { u -> onAdd(AddRequest(listOf(u), null, settings.threads, null), emptyMap(), null) }; state.toast = "${urls.size} downloads added"; state.tab = Tab.DOWNLOADS },
                onDismiss = { state.crawl = null })
        }
        state.torrentPick?.let { url ->
            val id = remember(url) { java.util.UUID.randomUUID().toString() }
            TorrentFilesDialog(load = { extras.torrentFiles(id, url) },
                onConfirm = { files -> extras.addTorrent(id, url, files); state.torrentPick = null; state.toast = "Torrent added"; state.tab = Tab.DOWNLOADS },
                onDismiss = { state.torrentPick = null })
        }
    }
}
