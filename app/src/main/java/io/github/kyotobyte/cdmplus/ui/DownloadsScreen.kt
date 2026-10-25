package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kyotobyte.cdmplus.engine.Category
import io.github.kyotobyte.cdmplus.engine.DownloadItem
import io.github.kyotobyte.cdmplus.engine.Status
import io.github.kyotobyte.cdmplus.engine.categoryOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface DownloadActions {
    fun pause(id: String); fun resume(id: String); fun retry(id: String); fun cancel(id: String)
    fun open(item: DownloadItem); fun share(item: DownloadItem); fun delete(item: DownloadItem, withFile: Boolean)
    fun relink(item: DownloadItem, url: String) {}
    fun checksum(item: DownloadItem, onResult: (io.github.kyotobyte.cdmplus.engine.Checksums.Result?) -> Unit) {}
    fun extract(item: DownloadItem) {}
    /** Keep only the music track of a finished video (saved as .m4a next to it). */
    fun extractMusic(item: DownloadItem) {}
    fun stopSeeding(id: String) {}
    fun clipboardText(): String = ""
}

@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    speeds: Map<String, Long>,
    actions: DownloadActions,
    onAdd: () -> Unit,
    clipboardUrl: String? = null,
    onClipboardUse: () -> Unit = {},
    onClipboardDismiss: () -> Unit = {},
    tick: Int = 0,
    initialTab: Int = 0,
    history: List<Long> = emptyList(),
    onScanQr: (() -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var category by rememberSaveable { mutableStateOf(Category.ALL) }
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }
    val accent = LocalAccent.current
    val tabs = listOf("All", "Active", "Done", "Failed")
    fun inTab(it: DownloadItem, t: Int) = when (t) {
        1 -> it.status in setOf(Status.RUNNING, Status.QUEUED, Status.PAUSED, Status.SCHEDULED)
        2 -> it.status == Status.DONE
        3 -> it.status == Status.FAILED || it.status == Status.CANCELED
        else -> true
    }
    val counts = tabs.indices.map { t -> items.count { inTab(it, t) } }
    val shown = items.filter { inTab(it, tab) && (category == Category.ALL || categoryOf(it.fileName, it.mimeType) == category) }
    val totalSpeed = speeds.values.sum()

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 18.dp, 16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CDM+", style = Type.H1.copy(color = accent))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (totalSpeed > 0) "▼ ${formatBytes(totalSpeed)}/s · ${items.count { it.status == Status.RUNNING }} running" else "${items.size} downloads",
                    style = Type.Label.copy(color = Px.Dim),
                )
            }
            if (onScanQr != null) { PixelIconButton(PxIcons.Qr, onScanQr, accent); Spacer(Modifier.width(8.dp)) }
            PixelButton("Add", onAdd, icon = PxIcons.Plus)
        }
        io.github.kyotobyte.cdmplus.update.UpdateBanners()
        if (history.any { it > 0 } || totalSpeed > 0) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                PixelSpeedGraph(history, accent, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("LIVE SPEED · 60S", style = Type.Label.copy(color = Px.Dim, fontSize = Type.Label.fontSize * 0.8f), modifier = Modifier.weight(1f))
                    Text("PEAK ${formatBytes(history.maxOrNull() ?: 0)}/S", style = Type.Label.copy(color = Px.Dim, fontSize = Type.Label.fontSize * 0.8f))
                }
            }
        }
        if (clipboardUrl != null) {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().pixelBorder(accent).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelIcon(PxIcons.Clip, accent, 14.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("LINK IN CLIPBOARD", style = Type.Label.copy(color = accent))
                    Text(clipboardUrl, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("GET", style = Type.Label.copy(color = accent), modifier = Modifier.clickable(onClick = onClipboardUse).padding(8.dp))
                Text("X", style = Type.Label.copy(color = Px.Dim), modifier = Modifier.clickable(onClick = onClipboardDismiss).padding(8.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        PixelTabs(tabs, tab, { tab = it }, Modifier.padding(horizontal = 16.dp), counts)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { c -> PixelChip(c.label, c == category) { category = c } }
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PixelIcon(PxIcons.Download, Px.Line, 48.dp)
                Spacer(Modifier.height(16.dp))
                Text("NOTHING HERE YET", style = Type.H2.copy(color = Px.Dim))
                Spacer(Modifier.height(8.dp))
                Text("Paste a link, share one from any app, or use the browser tab.", style = Type.Small)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { item ->
                    DownloadRow(item, speeds[item.id] ?: 0, actions, tick) { confirmDelete = item }
                }
            }
        }
    }
    confirmDelete?.let { item ->
        PixelDialog("Delete?", onDismiss = { confirmDelete = null }) {
            Text(item.fileName, style = Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("List only", { actions.delete(item, false); confirmDelete = null }, filled = false, modifier = Modifier.weight(1f))
                PixelButton("+ File", { actions.delete(item, true); confirmDelete = null }, color = Px.Red, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DownloadRow(item: DownloadItem, speed: Long, actions: DownloadActions, tick: Int, onDelete: () -> Unit) {
    val accent = LocalAccent.current
    var relink by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var sums by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var sumResult by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.kyotobyte.cdmplus.engine.Checksums.Result?>(null) }
    relink?.let { cur ->
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(cur) }
        PixelDialog("Refresh link", onDismiss = { relink = null }) {
            Text("Paste a fresh link for this file. What's already downloaded is kept when the file is the same.", style = Type.Small)
            Spacer(Modifier.height(8.dp))
            PixelField(text, { text = it }, "https://...", singleLine = false, minHeight = 60)
            Spacer(Modifier.height(10.dp))
            PixelButton("Update + resume", { actions.relink(item, text); relink = null }, modifier = Modifier.fillMaxWidth())
        }
    }
    sums?.let { msg ->
        PixelDialog("Checksum", onDismiss = { sums = null; sumResult = null }) {
            val r = sumResult
            if (r == null) Text(if (msg == "…") "Calculating…" else msg, style = Type.Small) else {
                Text("MD5", style = Type.Label.copy(color = Px.Dim)); Text(r.md5, style = Type.Small.copy(color = Px.Text))
                Spacer(Modifier.height(6.dp))
                Text("SHA-1", style = Type.Label.copy(color = Px.Dim)); Text(r.sha1, style = Type.Small.copy(color = Px.Text))
                Spacer(Modifier.height(6.dp))
                Text("SHA-256", style = Type.Label.copy(color = Px.Dim)); Text(r.sha256, style = Type.Small.copy(color = Px.Text))
                Spacer(Modifier.height(10.dp))
                val clip = actions.clipboardText()
                val match = r.matches(clip)
                Text(
                    when {
                        clip.isBlank() -> "Copy the expected hash, then reopen to compare."
                        match != null -> "✓ Matches the copied $match"
                        else -> "✗ Copied text doesn't match"
                    },
                    style = Type.BodyStrong.copy(color = if (match != null) accent else Px.Dim),
                )
            }
        }
    }
    val statusColor = when (item.status) {
        Status.RUNNING -> accent
        Status.DONE -> accent
        Status.FAILED, Status.CANCELED -> Px.Red
        Status.PAUSED, Status.SCHEDULED -> Px.Amber
        Status.QUEUED -> Px.Dim
    }
    Column(Modifier.fillMaxWidth().background(Px.Panel).pixelBorder(if (item.status == Status.RUNNING) accent.copy(alpha = 0.6f) else Px.Line).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).pixelBorder(statusColor, 1.dp), contentAlignment = Alignment.Center) {
                Text(extLabel(item.fileName), style = Type.Label.copy(color = statusColor, fontSize = Type.Label.fontSize * 0.75f))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(item, speed), style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.status != Status.DONE) {
            Spacer(Modifier.height(10.dp))
            PixelProgress(item.progress, statusColor, Modifier.fillMaxWidth(), indeterminate = item.status == Status.RUNNING && item.totalBytes <= 0, tick = tick)
            if (item.segments.size > 1 && item.totalBytes > 0 && item.status != Status.QUEUED) {
                Spacer(Modifier.height(6.dp))
                SegmentMap(item, statusColor, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (item.status) {
                    Status.RUNNING -> "${(item.progress * 100).toInt()}%  ·  ${item.segments.size.coerceAtLeast(1)}x"
                    Status.DONE -> formatBytes(item.totalBytes)
                    else -> item.status.name
                },
                style = Type.Label.copy(color = statusColor), modifier = Modifier.weight(1f),
            )
            when (item.status) {
                Status.RUNNING, Status.QUEUED -> {
                    PixelIconButton(PxIcons.Pause, { actions.pause(item.id) })
                    PixelIconButton(PxIcons.Close, { actions.cancel(item.id) }, Px.Red)
                }
                Status.PAUSED, Status.SCHEDULED -> {
                    PixelIconButton(PxIcons.Play, { actions.resume(item.id) }, accent)
                    PixelIconButton(PxIcons.Close, { actions.cancel(item.id) }, Px.Red)
                }
                Status.FAILED, Status.CANCELED -> {
                    PixelIconButton(PxIcons.Link, { relink = item.url }, Px.Amber)
                    PixelIconButton(PxIcons.Retry, { actions.retry(item.id) }, accent)
                    PixelIconButton(PxIcons.Trash, onDelete, Px.Red)
                }
                Status.DONE -> {
                    if (item.fileName.endsWith(".zip", true)) PixelIconButton(PxIcons.Unzip, { actions.extract(item) })
                    if (item.seeding) Text("STOP SEED", style = Type.Label.copy(color = Px.Amber), modifier = Modifier.clickable { actions.stopSeeding(item.id) }.padding(6.dp))
                    if (categoryOf(item.fileName, item.mimeType) == Category.VIDEO) PixelIconButton(PxIcons.Music, { actions.extractMusic(item) })
                    PixelIconButton(PxIcons.Hash, { sums = "…"; actions.checksum(item) { r -> sumResult = r; sums = if (r == null) "Could not read the file" else "" } })
                    PixelIconButton(PxIcons.Open, { actions.open(item) }, accent)
                    PixelIconButton(PxIcons.Share, { actions.share(item) })
                    PixelIconButton(PxIcons.Trash, onDelete, Px.Red)
                }
            }
        }
    }
}

private fun extLabel(name: String): String {
    val e = name.substringAfterLast('.', "").uppercase().take(4)
    return e.ifEmpty { "FILE" }
}

private val timeFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

fun statusLine(item: DownloadItem, speed: Long): String = when (item.status) {
    Status.RUNNING -> if (item.isTorrent) {
        val size = if (item.totalBytes > 0) "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}" else "Getting torrent info..."
        "Torrent · ${item.torrentPeers} peers · $size · ${formatBytes(speed)}/s"
    } else if (item.hlsTotal > 0) "Stream · part ${item.hlsDone}/${item.hlsTotal} · ${formatBytes(item.downloadedBytes)} · ${formatBytes(speed)}/s" else {
        val size = if (item.totalBytes > 0) "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}" else formatBytes(item.downloadedBytes)
        "$size · ${formatBytes(speed)}/s · ETA ${formatEta(item.totalBytes - item.downloadedBytes, speed)}"
    }
    Status.QUEUED -> item.waitingReason ?: "Queued"
    Status.PAUSED -> "Paused · ${formatBytes(item.downloadedBytes)}${if (item.totalBytes > 0) " / " + formatBytes(item.totalBytes) else ""}"
    Status.SCHEDULED -> "Starts ${timeFmt.format(Date(item.scheduledAt ?: 0))}"
    Status.DONE -> if (item.seeding) "Seeding · ${item.torrentPeers} peers · uploaded ${formatBytes(item.uploadedBytes)}" else
        (item.error?.let { "$it · " } ?: "") + "Saved to Downloads/CDM+ · ${timeFmt.format(Date(item.finishedAt ?: item.createdAt))}"
    Status.FAILED -> item.error ?: "Failed"
    Status.CANCELED -> "Canceled"
}


/** Pixel map of each connection's part: where it starts and how much of it is done. */
@Composable
fun SegmentMap(item: DownloadItem, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val total = item.totalBytes.toFloat()
    androidx.compose.foundation.Canvas(modifier.height(8.dp).background(Px.Panel2)) {
        item.segments.forEach { s ->
            if (s.end < 0) return@forEach
            val x0 = s.start / total * size.width
            val w = (s.length / total * size.width)
            val dw = (s.done.coerceAtMost(s.length) / total * size.width)
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(x0, 0f), size = androidx.compose.ui.geometry.Size(dw, size.height))
            drawRect(Px.Bg, topLeft = androidx.compose.ui.geometry.Offset(x0 + w - 2f, 0f), size = androidx.compose.ui.geometry.Size(2f, size.height))
        }
    }
}
