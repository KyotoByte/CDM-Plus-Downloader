package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kyotobyte.cdmplus.engine.LinkCrawler
import io.github.kyotobyte.cdmplus.engine.SegmentedDownloader
import io.github.kyotobyte.cdmplus.engine.TorrentDownloader
import io.github.kyotobyte.cdmplus.grab.GrabOption
import io.github.kyotobyte.cdmplus.grab.GrabResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/** What the grabber asks the queue to download. */
data class GrabRequest(
    val url: String, val fileName: String, val headers: Map<String, String>,
    val audioUrl: String? = null, val postProcess: String? = null, val mime: String? = null, val source: String? = null,
    val subDir: String? = null,
)

/** How many files of a shared folder the sheet lists one by one (all of them can still be selected). */
const val FOLDER_ROWS = 400

/** Downloads/CDM+/<folder name>/<path inside the folder>. */
fun folderSubDir(folder: String, path: String): String? = SegmentedDownloader.sanitizeDir(if (path.isEmpty()) folder.replace('/', '_') else folder.replace('/', '_') + "/" + path)

/** Builds a clean file name like "TikTok_bbcnews_Moon mission_2.mp4". */
fun grabName(r: GrabResult, part: String?, ext: String): String =
    SegmentedDownloader.sanitize(listOfNotNull(r.platform, r.author, r.title.take(40).trim(), part).joinToString("_") + "." + ext)

fun musicRequest(r: GrabResult): GrabRequest? {
    val name = SegmentedDownloader.sanitize((r.musicTitle ?: "${r.title} audio").take(80))
    r.musicUrl?.let { return GrabRequest(it, "$name.mp3", r.headers, source = r.platform) }
    val all = r.items.flatMap { it.options }
    all.firstOrNull { it.kind == "audio" && it.ext == "m4a" }?.let { return GrabRequest(it.url, "$name.m4a", r.headers, source = r.platform) }
    all.firstOrNull { it.kind == "audio" }?.let { return GrabRequest(it.url, "$name.${it.ext}", r.headers, source = r.platform) }
    // No separate audio file: download the smallest video with sound and keep only its music track.
    val v = all.filter { it.kind == "video" && it.audioUrl == null }.minByOrNull { it.width * it.height } ?: return null
    return GrabRequest(v.url, "$name.mp4", r.headers, postProcess = "extract_audio", source = r.platform)
}

fun optionRequest(r: GrabResult, o: GrabOption, part: String?): GrabRequest =
    GrabRequest(o.url, o.fileName?.let { SegmentedDownloader.sanitize(it) } ?: grabName(r, part, o.ext), r.headers, audioUrl = o.audioUrl, postProcess = o.postProcess ?: if (o.audioUrl != null) "merge" else null, source = r.platform)

/**
 * Grabber sheet: shows what a link contains (Instagram / TikTok / YouTube...), with a quality picker per item,
 * select-all for carousels, "Extract music", subtitles, and "Download all" for playlists.
 */
@Composable
fun GrabDialog(
    url: String,
    load: suspend () -> GrabResult,
    resolve: suspend (String) -> GrabResult,
    onDownload: (List<GrabRequest>) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalAccent.current
    var result by remember { mutableStateOf<GrabResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateMapOf<Int, Int>() }
    val selected = remember { mutableStateMapOf<Int, Boolean>() }
    val subs = remember { mutableStateMapOf<Int, Boolean>() }
    val folderPick = remember { mutableStateMapOf<Int, Boolean>() }
    var plQuality by remember { mutableIntStateOf(720) }
    var plProgress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var errorDetails by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var finding by remember { mutableStateOf(false) }
    val autoFind = remember(url) { io.github.kyotobyte.cdmplus.grab.SocialGrabber.platformOf(url) == "Instagram" && io.github.kyotobyte.cdmplus.grab.SocialGrabber.isVideoPost(url) }
    LaunchedEffect(url) {
        try {
            val r = load()
            // A reel that came back without its video: go find it in a hidden player instead of showing a cover.
            if (autoFind && r.items.none { i -> i.options.any { it.kind == "video" } }) finding = true else result = r
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (autoFind) { finding = true; return@LaunchedEffect }
            error = e.message ?: "Couldn't read this link (${e.javaClass.simpleName})"
            errorDetails = (e as? io.github.kyotobyte.cdmplus.grab.GrabException)?.details
                ?: "CDM+ grab error\nLink: $url\nAndroid SDK ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}\n\n${e.stackTraceToString().take(6000)}"
        }
    }
    PixelDialog(result?.platform?.uppercase()?.let { "GRAB · $it" } ?: "GRAB MEDIA", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val r = result
            when {
                error != null -> {
                    Text(error!!, style = Type.Body.copy(color = Px.Red))
                    Spacer(Modifier.height(10.dp))
                    PixelButton("Copy error details", {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(errorDetails ?: error!!))
                        android.widget.Toast.makeText(ctx, "Error details copied. Paste them to the developer.", android.widget.Toast.LENGTH_SHORT).show()
                    }, filled = false, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PixelButton("Close", onDismiss, filled = false, modifier = Modifier.weight(1f))
                        if (!error!!.startsWith("Protected")) PixelButton("Open in browser", { onOpenInBrowser(url) }, modifier = Modifier.weight(1f), icon = PxIcons.Globe)
                    }
                }
                finding -> ReelFinder(url,
                    onFound = { found -> result = found; finding = false },
                    onFail = { msg, det -> error = msg; errorDetails = "CDM+ grab error\nLink: $url\nAndroid SDK ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}\n\n$det"; finding = false })
                r == null -> {
                    Text("Reading the post…", style = Type.Label.copy(color = Px.Dim))
                    Spacer(Modifier.height(10.dp))
                    PixelProgress(0f, accent, Modifier.fillMaxWidth(), indeterminate = true)
                }
                r.folderFiles.isNotEmpty() -> {
                    val files = r.folderFiles
                    val sel = files.indices.filter { folderPick[it] != false }
                    val total = files.sumOf { it.bytes.coerceAtLeast(0) }
                    Text(r.title, style = Type.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${files.size} file${if (files.size == 1) "" else "s"} · ${formatBytes(total)} · saved to Downloads/CDM+${if (files.size == 1 && files[0].path.isEmpty()) "" else "/" + SegmentedDownloader.sanitize(r.title)}", style = Type.Small)
                    Spacer(Modifier.height(8.dp))
                    val all = sel.size == files.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${sel.size} SELECTED · ${formatBytes(sel.sumOf { files[it].bytes.coerceAtLeast(0) })}", style = Type.Label, modifier = Modifier.weight(1f))
                        PixelChip(if (all) "Select none" else "Select all", all) { val v = !all; files.indices.forEach { folderPick[it] = v } }
                    }
                    Spacer(Modifier.height(8.dp))
                    var lastPath: String? = null
                    files.take(FOLDER_ROWS).forEachIndexed { i, f ->
                        if (f.path != lastPath) {
                            lastPath = f.path
                            val depth = if (f.path.isEmpty()) 0 else f.path.count { it == '/' } + 1
                            val dirIdx = files.indices.filter { files[it].path == f.path }
                            val dirOn = dirIdx.all { folderPick[it] != false }
                            Row(Modifier.fillMaxWidth().clickable { val v = !dirOn; dirIdx.forEach { folderPick[it] = v } }.padding(top = 6.dp, bottom = 2.dp, start = (depth.coerceAtMost(6) * 8).dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).pixelBorder(Px.Dim).background(if (dirOn) Px.Dim else Px.Panel))
                                Spacer(Modifier.width(6.dp))
                                Text("▸ " + (if (f.path.isEmpty()) r.title else f.path.substringAfterLast('/')) + "/", style = Type.Label.copy(color = Px.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val on = folderPick[i] != false
                        val depth = if (f.path.isEmpty()) 1 else f.path.count { it == '/' } + 2
                        Row(Modifier.fillMaxWidth().clickable { folderPick[i] = !on }.padding(vertical = 4.dp).padding(start = (depth.coerceAtMost(7) * 8).dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).pixelBorder(accent).background(if (on) accent else Px.Panel))
                            Spacer(Modifier.width(8.dp))
                            Text(f.name, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            Text(if (f.bytes >= 0) formatBytes(f.bytes) else "", style = Type.Small.copy(color = Px.Dim))
                        }
                    }
                    if (files.size > FOLDER_ROWS) Text("… +${files.size - FOLDER_ROWS} more files (included in Select all)", style = Type.Small)
                    Spacer(Modifier.height(12.dp))
                    plProgress?.let { Text(it, style = Type.Label.copy(color = accent)); Spacer(Modifier.height(8.dp)) }
                    PixelButton(if (plProgress == null) "Download ${sel.size} file${if (sel.size == 1) "" else "s"}" else "Working…", {
                        scope.launch {
                            val picked = sel.map { files[it] }
                            var done = 0
                            plProgress = "Getting download links 0/${picked.size}…"
                            val gate = kotlinx.coroutines.sync.Semaphore(4)
                            val reqs = picked.map { f ->
                                async {
                                    gate.withPermit {
                                        val dir = if (files.size == 1 && f.path.isEmpty()) null else folderSubDir(r.title, f.path)
                                        val res = when {
                                            else -> runCatching { resolve(f.link) }.map { one -> optionRequest(one, one.items.first().options.first(), null).copy(subDir = dir) }
                                        }
                                        done++; plProgress = "Getting download links $done/${picked.size}…"
                                        res
                                    }
                                }
                            }.awaitAll()
                            val ok = reqs.mapNotNull { it.getOrNull() }
                            val firstErr = reqs.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                            if (ok.isNotEmpty()) onDownload(ok)
                            plProgress = if (firstErr == null) null else "Added ${ok.size} of ${picked.size} · $firstErr"
                            if (firstErr == null) onDismiss()
                        }
                    }, enabled = plProgress == null && sel.isNotEmpty(), modifier = Modifier.fillMaxWidth(), icon = PxIcons.Download)
                }
                r.playlist.isNotEmpty() -> {
                    Text(r.title, style = Type.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${r.playlist.size} videos${r.author?.let { " · $it" } ?: ""}", style = Type.Small)
                    Spacer(Modifier.height(10.dp))
                    Text("QUALITY FOR ALL", style = Type.Label.copy(color = Px.Dim))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(360 to "360p", 720 to "720p", 1080 to "1080p", 0 to "Audio").forEach { (q, l) -> PixelChip(l, plQuality == q) { plQuality = q } }
                    }
                    Spacer(Modifier.height(10.dp))
                    r.playlist.take(30).forEachIndexed { i, (t, _) -> Text("${i + 1}. $t", style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (r.playlist.size > 30) Text("… +${r.playlist.size - 30} more", style = Type.Small)
                    Spacer(Modifier.height(12.dp))
                    plProgress?.let { Text(it, style = Type.Label.copy(color = accent)); Spacer(Modifier.height(8.dp)) }
                    PixelButton(if (plProgress == null) "Download all ${r.playlist.size}" else "Working…", {
                        scope.launch {
                            var n = 0; var fail = 0
                            for ((_, u) in r.playlist) {
                                plProgress = "Reading ${n + fail + 1} of ${r.playlist.size}…"
                                val one = runCatching { resolve(u) }.getOrNull()
                                val opts = one?.items?.firstOrNull()?.options.orEmpty()
                                val pick = if (plQuality == 0) opts.firstOrNull { it.kind == "audio" && it.ext == "m4a" } ?: opts.firstOrNull { it.kind == "audio" }
                                else opts.filter { it.kind == "video" && it.height <= plQuality && (it.audioUrl == null || it.ext == "mp4") }.maxByOrNull { it.height * 10 + if (it.ext == "mp4") 1 else 0 }
                                if (one != null && pick != null) { onDownload(listOf(optionRequest(one, pick, null))); n++ } else fail++
                            }
                            plProgress = "Added $n${if (fail > 0) " · $fail unavailable" else ""}"
                        }
                    }, enabled = plProgress == null, modifier = Modifier.fillMaxWidth(), icon = PxIcons.Download)
                }
                else -> {
                    Text(r.title, style = Type.BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    r.author?.let { Text("@$it".replace("@@", "@"), style = Type.Small) }
                    Spacer(Modifier.height(10.dp))
                    if (r.items.size > 1) {
                        val all = r.items.all { selected[it.index] != false }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.items.size} ITEMS (CAROUSEL)", style = Type.Label, modifier = Modifier.weight(1f))
                            PixelChip(if (all) "Select none" else "Select all", all) { val v = !all; r.items.forEach { selected[it.index] = v } }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    r.items.forEach { item ->
                        val on = selected[item.index] != false
                        Column(Modifier.fillMaxWidth().pixelBorder(if (on) accent else Px.Line).padding(8.dp)) {
                            if (r.items.size > 1) Row(Modifier.clickable { selected[item.index] = !on }, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).pixelBorder(accent).background(if (on) accent else Px.Panel))
                                Spacer(Modifier.width(8.dp))
                                Text("Item ${item.index + 1} · ${item.options.firstOrNull()?.kind ?: ""}", style = Type.Label)
                            }
                            item.options.forEachIndexed { oi, o ->
                                val pick = (chosen[item.index] ?: 0) == oi
                                Row(Modifier.fillMaxWidth().clickable { chosen[item.index] = oi }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).background(if (pick) accent else Px.Line))
                                    Spacer(Modifier.width(8.dp))
                                    Text(o.label + if (o.bytes > 0) " · ${formatBytes(o.bytes)}" else "", style = Type.Body.copy(color = if (pick) Px.Text else Px.Dim), modifier = Modifier.weight(1f))
                                    if (o.audioUrl != null) Text("+AUDIO", style = Type.Label.copy(color = Px.Dim))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (r.subtitles.isNotEmpty()) {
                        Text("SUBTITLES", style = Type.Label.copy(color = Px.Dim))
                        r.subtitles.take(12).forEachIndexed { i, s ->
                            val on = subs[i] == true
                            Row(Modifier.fillMaxWidth().clickable { subs[i] = !on }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).pixelBorder(accent).background(if (on) accent else Px.Panel)); Spacer(Modifier.width(8.dp))
                                Text("${s.label} (.${s.ext})", style = Type.Small)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val music = musicRequest(r)
                        if (music != null) PixelButton("Extract music", { onDownload(listOf(music)); onDismiss() }, filled = false, modifier = Modifier.weight(1f))
                        PixelButton("Download", {
                            val reqs = r.items.filter { r.items.size == 1 || selected[it.index] != false }.mapNotNull { item ->
                                val o = item.options.getOrNull(chosen[item.index] ?: 0) ?: return@mapNotNull null
                                optionRequest(r, o, if (r.items.size > 1) "${item.index + 1}" else null)
                            } + r.subtitles.filterIndexed { i, _ -> subs[i] == true }.map { s -> GrabRequest(s.url, grabName(r, s.label.substringAfter(": ").take(20), s.ext), r.headers, source = r.platform) }
                            onDownload(reqs); onDismiss()
                        }, modifier = Modifier.weight(1f), icon = PxIcons.Download)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Only public posts, or what you can see logged in inside the CDM+ browser.", style = Type.Small)
                }
            }
        }
    }
}

/** Link grabber with crawl depth. */
@Composable
fun CrawlDialog(start: String, depth0: Int, sameSite0: Boolean, crawl: suspend (String, LinkCrawler.Options, (Int, Int) -> Unit) -> List<LinkCrawler.Found>, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    var depth by remember { mutableIntStateOf(depth0) }
    var same by remember { mutableStateOf(sameSite0) }
    var preset by remember { mutableStateOf("All files") }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<LinkCrawler.Found>?>(null) }
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    PixelDialog("LINK GRABBER", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(start, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("CRAWL DEPTH", style = Type.Label, modifier = Modifier.weight(1f)); PixelStepper(depth, 1..3, { depth = it }) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("SAME SITE ONLY", style = Type.Label, modifier = Modifier.weight(1f)); PixelSwitch(same) { same = it } }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf("All files") + LinkCrawler.PRESETS.keys).take(4).forEach { p -> PixelChip(p, preset == p) { preset = p } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LinkCrawler.PRESETS.keys.drop(3).forEach { p -> PixelChip(p, preset == p) { preset = p } }
            }
            Spacer(Modifier.height(10.dp))
            PixelButton(if (status == null || found != null) "Scan" else "Scanning…", {
                found = null; status = "Starting…"
                scope.launch {
                    val exts = LinkCrawler.PRESETS[preset] ?: LinkCrawler.PRESETS.values.flatten().toSet()
                    val res = runCatching { crawl(start, LinkCrawler.Options(depth, same, exts)) { p, f -> status = "$p pages read · $f files" } }.getOrElse { emptyList() }
                    found = res; res.forEach { picked[it.url] = true }
                    status = "${res.size} files found"
                }
            }, modifier = Modifier.fillMaxWidth())
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Type.Label.copy(color = accent)) }
            found?.let { list ->
                Spacer(Modifier.height(8.dp))
                list.take(200).forEach { f ->
                    val on = picked[f.url] == true
                    Row(Modifier.fillMaxWidth().clickable { picked[f.url] = !on }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).pixelBorder(accent).background(if (on) accent else Px.Panel)); Spacer(Modifier.width(8.dp))
                        Text(f.name, style = Type.Small.copy(color = Px.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(f.ext.uppercase(), style = Type.Label.copy(color = Px.Dim))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val n = list.count { picked[it.url] == true }
                PixelButton("Download $n", { onAdd(list.filter { picked[it.url] == true }.map { it.url }); onDismiss() }, enabled = n > 0, modifier = Modifier.fillMaxWidth(), icon = PxIcons.Download)
            }
        }
    }
}

/** Pick which files inside a torrent to download. */
@Composable
fun TorrentFilesDialog(load: suspend () -> List<TorrentDownloader.TFile>, onConfirm: (List<Int>?) -> Unit, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    var files by remember { mutableStateOf<List<TorrentDownloader.TFile>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val pick = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(Unit) { runCatching { load() }.onSuccess { files = it; it.forEach { f -> pick[f.index] = true } }.onFailure { err = it.message } }
    PixelDialog("FILES IN TORRENT", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val fs = files
            when {
                err != null -> { Text(err!!, style = Type.Body.copy(color = Px.Red)); Spacer(Modifier.height(10.dp)); PixelButton("Download everything", { onConfirm(null) }, modifier = Modifier.fillMaxWidth()) }
                fs == null -> { Text("Getting the file list…", style = Type.Label.copy(color = Px.Dim)); Spacer(Modifier.height(8.dp)); PixelProgress(0f, accent, Modifier.fillMaxWidth(), indeterminate = true) }
                else -> {
                    val sel = fs.filter { pick[it.index] == true }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${sel.size}/${fs.size} files · ${formatBytes(sel.sumOf { it.size })}", style = Type.Label, modifier = Modifier.weight(1f))
                        PixelChip(if (sel.size == fs.size) "None" else "All", sel.size == fs.size) { val v = sel.size != fs.size; fs.forEach { pick[it.index] = v } }
                    }
                    Spacer(Modifier.height(8.dp))
                    fs.take(500).forEach { f ->
                        val on = pick[f.index] == true
                        Row(Modifier.fillMaxWidth().clickable { pick[f.index] = !on }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).pixelBorder(accent).background(if (on) accent else Px.Panel)); Spacer(Modifier.width(8.dp))
                            Text(f.path.substringAfter('/'), style = Type.Small.copy(color = Px.Text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(formatBytes(f.size), style = Type.Label.copy(color = Px.Dim))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PixelButton("Download ${sel.size}", { onConfirm(if (sel.size == fs.size) null else sel.map { it.index }) }, enabled = sel.isNotEmpty(), modifier = Modifier.fillMaxWidth(), icon = PxIcons.Download)
                }
            }
        }
    }
}


/** Runs in the page: keeps every <video> playing muted and lists the .mp4 files the page has fetched. */
const val REEL_FIND_JS = """(function(){try{try{performance.setResourceTimingBufferSize(3000)}catch(e){}
var v=document.querySelectorAll('video'),out=[];for(var i=0;i<v.length;i++){var x=v[i];x.muted=true;x.playsInline=true;
if(x.paused){try{var p=x.play();if(p&&p.catch)p.catch(function(){})}catch(e){}}var s=x.currentSrc||x.src;if(s&&s.indexOf('http')==0)out.push(s)}
try{performance.getEntriesByType('resource').forEach(function(e){if(e.name.indexOf('.mp4')>0)out.push(e.name)})}catch(e){}
return out}catch(e){return []}})()"""

/**
 * Instagram hides some reels' video from server requests. This loads the reel in a small muted player
 * (same cookies as CDM+'s browser, so a login there counts), catches the video + sound files it streams,
 * and hands them to GRAB. Gives up after 15 s with a clear message.
 */
@Composable
fun ReelFinder(url: String, onFound: (io.github.kyotobyte.cdmplus.grab.GrabResult) -> Unit, onFail: (String, String) -> Unit) {
    val accent = LocalAccent.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val caught = remember { java.util.Collections.synchronizedList(ArrayList<String>()) }
    var status by remember { mutableStateOf("Finding video…") }
    val wv = remember {
        android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            runCatching { settings.userAgentString = WebPolicy.chromeLikeUa(android.webkit.WebSettings.getDefaultUserAgent(ctx)) }
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = request.url.toString()
                    if (io.github.kyotobyte.cdmplus.grab.SocialGrabber.igMediaKey(u) != null) caught.add(u)
                    return null // watch only, never re-fetch
                }
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean =
                    !(request.url.scheme == "http" || request.url.scheme == "https") // no jumping to the Instagram app
            }
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { wv.stopLoading(); wv.destroy() } } }
    LaunchedEffect(url) {
        wv.loadUrl(url)
        val start = System.currentTimeMillis(); var firstVideoAt = 0L
        while (System.currentTimeMillis() - start < 15_000) {
            kotlinx.coroutines.delay(600)
            val fromJs = kotlinx.coroutines.CompletableDeferred<List<String>>()
            wv.evaluateJavascript(REEL_FIND_JS) { raw -> fromJs.complete(runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw ?: "[]") }.getOrDefault(emptyList())) }
            val js = kotlinx.coroutines.withTimeoutOrNull(1500) { fromJs.await() }.orEmpty()
            val snap = synchronized(caught) { caught.toList() } + js
            val quick = io.github.kyotobyte.cdmplus.grab.SocialGrabber.fromSniffed("Instagram", snap) ?: continue
            if (firstVideoAt == 0L) { firstVideoAt = System.currentTimeMillis(); status = "Video found, getting the sound…" }
            // Done when the sound is found too, or after 4 s more (then check every file's real content).
            if (io.github.kyotobyte.cdmplus.grab.SocialGrabber.hasSound(quick) || System.currentTimeMillis() - firstVideoAt > 4000) {
                status = "Checking files…"
                val r = io.github.kyotobyte.cdmplus.grab.SocialGrabber.fromSniffedProbed("Instagram", snap) ?: quick
                if (io.github.kyotobyte.cdmplus.grab.SocialGrabber.hasSound(r) || System.currentTimeMillis() - firstVideoAt > 4000) { onFound(r); return@LaunchedEffect }
            }
        }
        val n = synchronized(caught) { caught.size }
        onFail("Couldn't get this reel's video in 15 s. Instagram may need you to log in for it: tap \"Open in browser\", log in to Instagram once, then share the reel to CDM+ again.",
            "ReelFinder timeout, media requests caught: $n, page: ${wv.url}")
    }
    Column {
        Box(Modifier.fillMaxWidth().height(180.dp).background(androidx.compose.ui.graphics.Color.Black)) {
            androidx.compose.ui.viewinterop.AndroidView({ wv }, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(status, style = Type.Label.copy(color = Px.Dim))
        Spacer(Modifier.height(6.dp))
        PixelProgress(0f, accent, Modifier.fillMaxWidth(), indeterminate = true)
    }
}
