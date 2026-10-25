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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kyotobyte.cdmplus.vpn.BlockSource
import io.github.kyotobyte.cdmplus.vpn.ListInfo
import io.github.kyotobyte.cdmplus.vpn.Upstream
import io.github.kyotobyte.cdmplus.vpn.VpnConfig

data class VpnUiState(
    val running: Boolean,
    val config: VpnConfig,
    val lists: Map<String, ListInfo>,
    val queries: Long,
    val blocked: Long,
    val lifetimeBlocked: Long,
    val recent: List<String>,
    val now: Long = System.currentTimeMillis(),
)

@Composable
fun VpnScreen(
    ui: VpnUiState,
    onToggle: (Boolean) -> Unit,
    onConfig: ((VpnConfig) -> VpnConfig) -> Unit,
    onUpdate: (BlockSource?) -> Unit,
) {
    val accent = LocalAccent.current
    val c = ui.config
    Column(Modifier.fillMaxSize().background(Px.Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("1.1.1.1 MODE", style = Type.H1.copy(color = accent))
        Spacer(Modifier.height(4.dp))
        Text("Encrypted DNS + ad-block for every app", style = Type.Small)
        Spacer(Modifier.height(16.dp))

        // Big status / toggle panel
        val on = ui.running
        Column(
            Modifier.fillMaxWidth().background(Px.Panel).pixelBorder(if (on) accent else Px.Line).clickable { onToggle(!on) }.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelIcon(PxIcons.Shield, if (on) accent else Px.Dim, 56.dp)
            Spacer(Modifier.height(14.dp))
            Text(if (on) "PROTECTED" else "OFF", style = Type.H1.copy(color = if (on) accent else Px.Dim))
            Spacer(Modifier.height(6.dp))
            Text(if (on) "DNS encrypted via ${c.upstream.title} · ads blocked system-wide" else "Tap to turn on", style = Type.Small)
            Spacer(Modifier.height(14.dp))
            PixelButton(if (on) "Turn off" else "Turn on", { onToggle(!on) }, filled = !on, modifier = Modifier.width(180.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("LOOKUPS", ui.queries.toString(), Modifier.weight(1f))
            Stat("BLOCKED", ui.blocked.toString(), Modifier.weight(1f), accent)
            val pct = if (ui.queries > 0) (ui.blocked * 100 / ui.queries) else 0
            Stat("BLOCK %", "$pct%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text("All-time blocked: ${ui.lifetimeBlocked}", style = Type.Small)
        Spacer(Modifier.height(16.dp))

        Section("Resolver (DNS-over-HTTPS)") {
            Upstream.entries.forEach { u ->
                val sel = u == c.upstream
                Row(
                    Modifier.fillMaxWidth().clickable { onConfig { it.copy(upstream = u) } }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(16.dp).pixelBorder(if (sel) accent else Px.Line).padding(4.dp).background(if (sel) accent else Px.Panel))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.title, style = Type.BodyStrong.copy(color = if (sel) accent else Px.Text))
                        Text(u.subtitle, style = Type.Small)
                    }
                }
            }
        }

        Section("Block lists") {
            BlockSource.entries.forEach { s ->
                val info = ui.lists[s.id] ?: ListInfo()
                val enabled = s.id in c.lists
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(s.title, style = Type.BodyStrong)
                        val sub = when {
                            info.busy -> "Updating..."
                            info.error != null -> "Update failed: ${info.error}"
                            info.count == 0 -> "Not downloaded yet"
                            else -> "%,d domains · updated %s".format(info.count, ago(ui.now - info.updatedAt))
                        }
                        Text(sub, style = Type.Small.copy(color = if (info.error != null) Px.Red else Px.Dim))
                    }
                    PixelIconButton(PxIcons.Retry, { onUpdate(s) }, Px.Dim, size = 32.dp)
                    Spacer(Modifier.width(6.dp))
                    PixelSwitch(enabled) { v -> onConfig { cfg -> cfg.copy(lists = if (v) cfg.lists + s.id else cfg.lists - s.id) } }
                }
            }
            Spacer(Modifier.height(8.dp))
            PixelButton("Update all lists", { onUpdate(null) }, filled = false, modifier = Modifier.fillMaxWidth())
        }

        Section("Your own lists") {
            var deny by remember(c.deny) { mutableStateOf(c.deny) }
            var allow by remember(c.allow) { mutableStateOf(c.allow) }
            Text("BLOCK THESE (one domain per line)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            PixelField(deny, { deny = it }, "ads.example.com", singleLine = false, minHeight = 70)
            Spacer(Modifier.height(10.dp))
            Text("ALWAYS ALLOW (fixes a broken site)", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            PixelField(allow, { allow = it }, "example.com", singleLine = false, minHeight = 70)
            Spacer(Modifier.height(10.dp))
            PixelButton("Save", { onConfig { it.copy(deny = deny, allow = allow) } }, modifier = Modifier.fillMaxWidth())
        }

        Section("Options") {
            Line("Encrypt CDM+ downloads too", "The app's own downloads also look up servers over DoH") {
                PixelSwitch(c.encryptDownloads) { v -> onConfig { it.copy(encryptDownloads = v) } }
            }
        }

        if (ui.recent.isNotEmpty()) Section("Recently blocked") {
            ui.recent.take(8).forEach { d ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Px.Red)); Spacer(Modifier.width(10.dp))
                    Text(d, style = Type.Body.copy(color = Px.Dim))
                }
            }
        }

        Section("What this does (honest)") {
            Text("✓ Encrypts the DNS lookups of every app on the phone (DNS-over-HTTPS to the resolver you pick).", style = Type.Body)
            Spacer(Modifier.height(6.dp))
            Text("✓ Blocks ad, tracker and malware domains system-wide using the lists above.", style = Type.Body)
            Spacer(Modifier.height(6.dp))
            Text("✗ It is NOT a full traffic tunnel like Cloudflare WARP: your IP address and the rest of your traffic are unchanged. WARP needs Cloudflare's own app.", style = Type.Body.copy(color = Px.Amber))
            Spacer(Modifier.height(6.dp))
            Text("Tip: if Android's Private DNS is set to a hostname, Android uses that instead. Set it to Off or Automatic. Works with a quick-settings tile too.", style = Type.Small)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = Px.Text) {
    Column(modifier.background(Px.Panel).pixelBorder().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.H2.copy(color = color))
        Spacer(Modifier.height(6.dp))
        Text(label, style = Type.Label.copy(color = Px.Dim, fontSize = Type.Label.fontSize * 0.85f))
    }
}

internal fun ago(ms: Long): String = when {
    ms < 90_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    ms < 86_400_000 -> "${ms / 3_600_000} h ago"
    else -> "${ms / 86_400_000} d ago"
}
