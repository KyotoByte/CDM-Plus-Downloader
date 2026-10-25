package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kyotobyte.cdmplus.data.PasswordVault
import io.github.kyotobyte.cdmplus.data.SavedLogin

/** Saved logins, hidden until fingerprint / face / screen lock unlocks them. */
@Composable
fun PasswordList(vault: PasswordVault, unlock: (String, (Boolean) -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<SavedLogin>>(emptyList()) }
    var shown by remember { mutableStateOf<String?>(null) }
    if (!open) {
        PixelButton("Show saved passwords", { unlock("Show saved passwords") { ok -> if (ok) { list = runCatching { vault.all() }.getOrDefault(emptyList()); open = true } } }, filled = false, icon = PxIcons.Key, modifier = Modifier.fillMaxWidth())
        Text("Encrypted with the Android Keystore. Fingerprint, face or screen lock needed.", style = Type.Small)
        return
    }
    if (list.isEmpty()) Text("No saved passwords yet. Log in to a site in the CDM+ browser and tap Save.", style = Type.Small)
    list.forEach { l ->
        val key = l.site + "|" + l.username
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.site, style = Type.BodyStrong)
                Text(l.username.ifBlank { "(no username)" } + "  ·  " + if (shown == key) l.password else "••••••••", style = Type.Small)
            }
            Text(if (shown == key) "HIDE" else "SHOW", style = Type.Label.copy(color = LocalAccent.current), modifier = Modifier.clickable { shown = if (shown == key) null else key }.padding(6.dp))
            Text("DEL", style = Type.Label.copy(color = Px.Red), modifier = Modifier.clickable { vault.delete(l); list = vault.all() }.padding(6.dp))
        }
    }
}
