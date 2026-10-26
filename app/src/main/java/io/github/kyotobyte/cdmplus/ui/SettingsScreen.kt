package io.github.kyotobyte.cdmplus.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kyotobyte.cdmplus.data.AppSettings

val ComingSoon = emptyList<String>()

@Composable
fun SettingsScreen(
    s: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onComingSoon: (String) -> Unit,
    onPickFolder: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onFloating: (Boolean) -> Unit = { v -> onChange { it.copy(floating = v) } },
    onExportSettings: () -> Unit = {},
    onImportSettings: () -> Unit = {},
    passwords: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().background(Px.Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("SETTINGS", style = Type.H1.copy(color = accent))
        Spacer(Modifier.height(18.dp))
        io.github.kyotobyte.cdmplus.update.UpdateSettingsSection(s, onChange)
        Section("Downloads") {
            Line("Threads per download", "Connections used for segmented downloads") { PixelStepper(s.threads, 1..32, { v -> onChange { it.copy(threads = v) } }) }
            Line("Simultaneous downloads", "Others wait in the queue") { PixelStepper(s.maxSimultaneous, 1..30, { v -> onChange { it.copy(maxSimultaneous = v) } }) }
            Line("Speed limit", if (s.speedLimitKb == 0) "Unlimited" else "${s.speedLimitKb} KB/s total") {
                PixelStepper(s.speedLimitKb / 256, 0..40, { v -> onChange { it.copy(speedLimitKb = v * 256) } }, "")
            }
            Line("Wi-Fi only", "Pause on mobile data, resume on Wi-Fi") { PixelSwitch(s.wifiOnly) { v -> onChange { it.copy(wifiOnly = v) } } }
            Line("Auto-resume", "Retry failed downloads when the network returns") { PixelSwitch(s.autoResume) { v -> onChange { it.copy(autoResume = v) } } }
            Line("Retries", if (s.retryCount == 0) "Unlimited automatic retries" else "${s.retryCount} automatic retries after an error") { PixelStepper(s.retryCount, 0..50, { v -> onChange { it.copy(retryCount = v) } }) }
            Line("Retry wait", "Seconds between retries") { PixelStepper(s.retryDelaySec, 1..120, { v -> onChange { it.copy(retryDelaySec = v) } }, "s") }
            Line("Stop on low battery", if (s.batteryStop == 0) "Off" else "Pause below ${s.batteryStop}% (not while charging)") { PixelStepper(s.batteryStop, 0..50, { v -> onChange { it.copy(batteryStop = (if (v > it.batteryStop) it.batteryStop + 5 else it.batteryStop - 5).coerceIn(0, 50)) } }, "%") }
            Line("Daily start", if (s.dailyStartHour < 0) "Off" else "Resume all at %02d:00 every day".format(s.dailyStartHour)) { PixelStepper(s.dailyStartHour, -1..23, { v -> onChange { it.copy(dailyStartHour = v) } }) }
            Line("Daily stop", if (s.dailyStopHour < 0) "Off" else "Pause all at %02d:00 every day".format(s.dailyStopHour)) { PixelStepper(s.dailyStopHour, -1..23, { v -> onChange { it.copy(dailyStopHour = v) } }) }
            Line("Floating speed window", "Small draggable speed box over other apps") { PixelSwitch(s.floating) { v -> onFloating(v) } }
            Spacer(Modifier.height(6.dp))
            Text("PARTS PER SITE (one per line: site.com=2)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            PixelField(s.domainParts, { v -> onChange { it.copy(domainParts = v) } }, "example.com=2", singleLine = false, minHeight = 50)
            Line("Clipboard links", "Offer to download copied links") { PixelSwitch(s.clipboardDetect) { v -> onChange { it.copy(clipboardDetect = v) } } }
            Line("Completion alerts", "Notify when a download finishes") { PixelSwitch(s.notifyOnComplete) { v -> onChange { it.copy(notifyOnComplete = v) } } }
        }
        Section("Look") {
            Text("ACCENT", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Accents.forEachIndexed { i, a ->
                    Box(
                        Modifier.size(36.dp).pixelBorder(if (i == s.accent) Px.Text else Px.Line).padding(4.dp).background(a.color)
                            .clickable { onChange { it.copy(accent = i) } },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(Accents.getOrElse(s.accent) { Accents[0] }.name.uppercase(), style = Type.Label.copy(color = accent))
            Spacer(Modifier.height(10.dp))
            Text("THEME", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelChip("Dark", s.themeMode == "dark" && !s.amoled) { onChange { it.copy(themeMode = "dark", amoled = false) } }
                PixelChip("AMOLED", s.themeMode != "light" && s.amoled) { onChange { it.copy(themeMode = "dark", amoled = true) } }
                PixelChip("Light", s.themeMode == "light") { onChange { it.copy(themeMode = "light", amoled = false) } }
            }
            Spacer(Modifier.height(12.dp))
            Text("CUSTOM ACCENT COLOUR (hex, blank = preset)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            val hexState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(if (s.customAccent >= 0) "#%06X".format(s.customAccent) else "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField(hexState.value, { v -> hexState.value = v.take(7); val c = parseHexColor(hexState.value); if (c != null) onChange { it.copy(customAccent = c) } else if (hexState.value.isBlank()) onChange { it.copy(customAccent = -1) } }, "#FF7A00", Modifier.weight(1f))
                Box(Modifier.size(34.dp).pixelBorder(Px.Text).padding(3.dp).background(parseHexColor(hexState.value)?.let { androidx.compose.ui.graphics.Color(0xFF000000 or it.toLong()) } ?: accent))
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0xFF2D55, 0xFF7A00, 0xFFD400, 0x00C2A8, 0x2E7DFF, 0x8C52FF, 0xEDEDED, 0x00E5FF).forEach { c ->
                    Box(Modifier.size(28.dp).pixelBorder(if (s.customAccent == c) Px.Text else Px.Line).padding(3.dp).background(androidx.compose.ui.graphics.Color(0xFF000000 or c.toLong())).clickable { hexState.value = "#%06X".format(c); onChange { it.copy(customAccent = c) } })
                }
            }
        }
        Section("Torrents") {
            Line("Seed after download", "Share back with other people until the limit below") { PixelSwitch(s.seedEnabled) { v -> onChange { it.copy(seedEnabled = v) } } }
            Line("Ratio limit", if (s.seedRatio <= 0f) "No limit (seed until you stop it)" else "Stop at %.1f (uploaded / downloaded)".format(s.seedRatio)) { PixelStepper((s.seedRatio * 2).toInt(), 0..20, { v -> onChange { it.copy(seedRatio = v / 2f) } }) }
            Line("Time limit", if (s.seedMinutes == 0) "No time limit" else "Stop after ${s.seedMinutes} min") { PixelStepper(s.seedMinutes / 15, 0..48, { v -> onChange { it.copy(seedMinutes = v * 15) } }) }
            Line("Upload speed limit", if (s.uploadLimitKb == 0) "Unlimited" else "${s.uploadLimitKb} KB/s") { PixelStepper(s.uploadLimitKb / 64, 0..64, { v -> onChange { it.copy(uploadLimitKb = v * 64) } }) }
            Text("Adding a torrent or magnet shows its file list so you can pick only what you want.", style = Type.Small)
        }
        Section("Browser") {
            Text("SEARCH ENGINE (address bar + home page)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.entries.forEach { e -> PixelChip(e.title, s.searchEngine == e.id) { onChange { it.copy(searchEngine = e.id) } } }
            }
            if (s.searchEngine == SearchEngine.CUSTOM.id) {
                Spacer(Modifier.height(8.dp))
                PixelField(s.customSearch, { v -> onChange { it.copy(customSearch = v.trim()) } }, "https://example.com/search?q=%s")
                Text(if (s.customSearch.contains("%s")) "Your words replace %s" else "Needs %s where the search words go (Google is used until then)", style = Type.Small)
            }
        }
        Section("Grabber & link crawler") {
            Text("Share or paste an Instagram, TikTok, YouTube, SoundCloud, Bandcamp or PeerTube link to pick a quality, grab whole carousels and playlists, or extract the music. In the browser: GRAB on the page.", style = Type.Small)
            Spacer(Modifier.height(8.dp))
            Line("Link crawler depth", "Pages followed from the start page (1 = this page only)") { PixelStepper(s.crawlDepth, 1..3, { v -> onChange { it.copy(crawlDepth = v) } }) }
            Line("Crawl same site only", "Don't follow links to other websites") { PixelSwitch(s.crawlSameSite) { v -> onChange { it.copy(crawlSameSite = v) } } }
            Text("DRM-protected video (Netflix, Prime, Hotstar…) shows \"Protected content, can't be downloaded\".", style = Type.Small)
        }
        Section("Passwords") {
            Line("Offer to save passwords", "In the CDM+ browser, never in incognito") { PixelSwitch(s.savePasswords) { v -> onChange { it.copy(savePasswords = v) } } }
            passwords?.invoke()
        }
        Section("Network") {
            Line("Browser ad-block", "Blocks ads + YouTube ads. Turn off per site with ADS in the browser. Adds the 1.1.1.1 lists while that mode is on") { PixelSwitch(s.adBlock) { v -> onChange { it.copy(adBlock = v) } } }
            Spacer(Modifier.height(6.dp))
            Text("USER AGENT (blank = default)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            PixelField(s.userAgent, { v -> onChange { it.copy(userAgent = v) } }, "Mozilla/5.0 ...")
            Spacer(Modifier.height(10.dp))
            Line("SOCKS5 proxy", "Off = HTTP proxy. Tor: Orbot on 127.0.0.1:9050") { PixelSwitch(s.proxySocks) { v -> onChange { it.copy(proxySocks = v) } } }
            PixelButton("Use Tor (Orbot)", { onChange { it.copy(proxySocks = true, proxyHost = "127.0.0.1", proxyPort = 9050) } }, filled = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("PROXY (blank = off)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField(s.proxyHost, { v -> onChange { it.copy(proxyHost = v.trim()) } }, "host", Modifier.weight(2f))
                PixelField(if (s.proxyPort == 0) "" else s.proxyPort.toString(), { v -> onChange { it.copy(proxyPort = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } }, "port", Modifier.weight(1f))
            }
        }
        Section("Storage") {
            Text(if (s.saveTreeUri.isBlank()) "Saving to Downloads/CDM+" else "Saving to your chosen folder", style = Type.Body)
            Text(if (s.saveTreeUri.isBlank()) "Android 10+: MediaStore, Android 7-9: public Downloads folder" else android.net.Uri.decode(s.saveTreeUri.substringAfterLast('/')), style = Type.Small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Pick folder", onPickFolder, filled = false, modifier = Modifier.weight(1f))
                if (s.saveTreeUri.isNotBlank()) PixelButton("Default", { onChange { it.copy(saveTreeUri = "") } }, filled = false, modifier = Modifier.weight(1f))
            }
        }
        Section("Backup") {
            Text("Export or import your download list (JSON or a plain list of links), or your settings", style = Type.Small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Export", onExport, filled = false, modifier = Modifier.weight(1f))
                PixelButton("Import", onImport, filled = false, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Export settings", onExportSettings, filled = false, modifier = Modifier.weight(1f))
                PixelButton("Import settings", onImportSettings, filled = false, modifier = Modifier.weight(1f))
            }
        }
        if (ComingSoon.isNotEmpty()) Section("Coming in next update") {
            ComingSoon.forEach { f ->
                Row(Modifier.fillMaxWidth().clickable { onComingSoon(f) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Px.Amber)); Spacer(Modifier.width(10.dp))
                    Text(f, style = Type.Body.copy(color = Px.Dim), modifier = Modifier.weight(1f))
                    Text("SOON", style = Type.Label.copy(color = Px.Amber))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text("CDM+ Downloader v1.0 Patch 1", style = Type.Label.copy(color = Px.Dim))
        Text("https://github.com/KyotoByte", style = Type.Label.copy(color = accent), modifier = Modifier.clickable {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/KyotoByte"))) }
        })
        Spacer(Modifier.height(6.dp))
        Text("Open source under GPL-3.0. Uses NewPipeExtractor (GPL-3.0), libtorrent4j (MIT), ZXing (Apache-2.0), jsoup (MIT), OkHttp (Apache-2.0). Full credits in CREDITS.md.", style = Type.Small)
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).background(Px.Panel).pixelBorder().padding(14.dp)) {
        Text(title.uppercase(), style = Type.H2.copy(color = LocalAccent.current))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun Line(title: String, sub: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Type.BodyStrong)
            Text(sub, style = Type.Small)
        }
        control()
    }
}
