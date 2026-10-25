package io.github.kyotobyte.cdmplus.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.data.AppSettings
import io.github.kyotobyte.cdmplus.ui.Line
import io.github.kyotobyte.cdmplus.ui.LocalAccent
import io.github.kyotobyte.cdmplus.ui.PixelButton
import io.github.kyotobyte.cdmplus.ui.PixelDialog
import io.github.kyotobyte.cdmplus.ui.PixelProgress
import io.github.kyotobyte.cdmplus.ui.PixelSwitch
import io.github.kyotobyte.cdmplus.ui.Px
import io.github.kyotobyte.cdmplus.ui.Section
import io.github.kyotobyte.cdmplus.ui.Type
import io.github.kyotobyte.cdmplus.ui.pixelBorder
import kotlinx.coroutines.launch

/** Top of the Downloads screen: update banner (until updated or closed for that version) + announcement card. */
@Composable
fun UpdateBanners() {
    val ctx = LocalContext.current
    val info by Updater.available.collectAsState()
    val dismissedVc by Updater.bannerDismissedVc.collectAsState()
    val notice by Updater.notice.collectAsState()
    val accent = LocalAccent.current
    info?.takeIf { it.versionCode > dismissedVc }?.let { u ->
        Row(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth().background(Px.Panel).pixelBorder(accent)
                .clickable { Updater.showDialog.value = true }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("NEW UPDATE AVAILABLE: ${UpdateCore.label(u).uppercase()}", style = Type.Label.copy(color = accent))
                if (u.notes.isNotBlank()) Text(u.notes.lineSequence().first().take(90), style = Type.Small)
            }
            Spacer(Modifier.width(8.dp))
            PixelButton("Update", { Updater.showDialog.value = true })
            Spacer(Modifier.width(6.dp))
            Text("✕", style = Type.BodyStrong.copy(color = Px.Dim), modifier = Modifier.clickable { Updater.dismissUpdateBanner(ctx) }.padding(6.dp))
        }
    }
    notice?.let { n ->
        Row(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth().background(Px.Panel).pixelBorder(Px.Amber).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (n.title.isNotBlank()) Text(n.title, style = Type.BodyStrong)
                if (n.message.isNotBlank()) Text(n.message, style = Type.Small)
                n.url?.let { url ->
                    Spacer(Modifier.height(4.dp))
                    Text("OPEN LINK", style = Type.Label.copy(color = accent), modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                }
            }
            Text("✕", style = Type.BodyStrong.copy(color = Px.Dim), modifier = Modifier.clickable { Updater.dismissNotice(ctx) }.padding(6.dp))
        }
    }
}

/** "Update available" dialog: download the right APK, check its signature, open the installer. */
@Composable
fun UpdateDialogHost() {
    val show by Updater.showDialog.collectAsState()
    val info by Updater.available.collectAsState()
    val dl by Updater.download.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }
    val u = info
    if (!show) return
    if (u == null) { LaunchedEffect(Unit) { Updater.showDialog.value = false }; return }
    val close = { Updater.showDialog.value = false; hint = null }
    PixelDialog("Update available", onDismiss = close) {
        Text("CDM+ ${UpdateCore.label(u)}", style = Type.BodyStrong.copy(color = LocalAccent.current))
        if (u.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) { Text(u.notes, style = Type.Small) }
        }
        Spacer(Modifier.height(14.dp))
        when (val d = dl) {
            is Updater.Dl.Downloading -> {
                PixelProgress(d.progress.coerceAtLeast(0f), LocalAccent.current, Modifier.fillMaxWidth(), indeterminate = d.progress < 0f)
                Spacer(Modifier.height(6.dp))
                Text(if (d.progress < 0f) "Downloading…" else "Downloading… ${(d.progress * 100).toInt()}%", style = Type.Small)
                Spacer(Modifier.height(10.dp))
                PixelButton("Cancel", { Updater.cancelDownload() }, filled = false, modifier = Modifier.fillMaxWidth())
            }
            is Updater.Dl.Ready -> {
                Text("Downloaded and signature checked ✓", style = Type.Small)
                hint?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Type.Small.copy(color = Px.Amber)) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Later", close, filled = false, modifier = Modifier.weight(1f))
                    PixelButton("Install", { hint = Updater.install(ctx, d.file) }, modifier = Modifier.weight(1f))
                }
            }
            is Updater.Dl.Error -> {
                Text(d.message, style = Type.Small.copy(color = Px.Amber))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Close", { Updater.download.value = Updater.Dl.Idle; close() }, filled = false, modifier = Modifier.weight(1f))
                    PixelButton("Retry", { Updater.startDownload(ctx.applicationContext, CdmApp.instance.appScope) }, modifier = Modifier.weight(1f))
                }
            }
            Updater.Dl.Idle -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Later", close, filled = false, modifier = Modifier.weight(1f))
                    PixelButton("Download", { scope.launch { Updater.startDownload(ctx.applicationContext, CdmApp.instance.appScope) } }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Settings → Updates: two switches and "Check for updates now". */
@Composable
fun UpdateSettingsSection(s: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Updater.status.collectAsState()
    val busy by Updater.checkingNow.collectAsState()
    Section("Updates") {
        Line("Update notifications", "Tell me when a new CDM+ version is out") { PixelSwitch(s.updateNotify) { v -> onChange { it.copy(updateNotify = v) } } }
        Line("Announcements", "News from the developer, shown once") { PixelSwitch(s.announcements) { v -> onChange { it.copy(announcements = v) } } }
        Spacer(Modifier.height(8.dp))
        PixelButton(if (busy) "Checking…" else "Check for updates now", {
            if (!busy) { Updater.status.value = "Checking…"; scope.launch { Updater.check(ctx.applicationContext, manual = true) } }
        }, filled = false, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, style = Type.Small) }
        Spacer(Modifier.height(6.dp))
        Text("Checks two public files on the developer's GitHub. Nothing about you is sent.", style = Type.Small)
    }
}
