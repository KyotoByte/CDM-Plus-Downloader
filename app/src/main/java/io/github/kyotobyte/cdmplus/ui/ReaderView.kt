package io.github.kyotobyte.cdmplus.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Password manager hooks the browser needs (implemented in MainActivity with the Keystore vault). */
interface VaultUi {
    fun enabled(): Boolean
    fun hasLogin(url: String): Boolean
    fun isSame(url: String, user: String, pass: String): Boolean
    fun save(url: String, user: String, pass: String)
    /** Asks for fingerprint / screen lock, then hands back the fill script. */
    fun fill(url: String, run: (String) -> Unit)
}

/** Reading mode: the article only, in a clean page with text size and day/night. */
@Composable
fun ReaderView(pageUrl: String, article: Reader.Article, onClose: () -> Unit) {
    var size by remember { mutableIntStateOf(18) }
    var dark by remember { mutableStateOf(!Px.light) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Px.Bg)) {
            Row(Modifier.fillMaxWidth().background(Px.Panel).pixelBorder().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("READING", style = Type.H2, modifier = Modifier.weight(1f))
                PixelButton("A-", { size = (size - 2).coerceAtLeast(12) }, filled = false)
                PixelButton("A+", { size = (size + 2).coerceAtMost(32) }, filled = false)
                PixelButton(if (dark) "Day" else "Night", { dark = !dark }, filled = false)
                PixelButton("X", onClose)
            }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false } },
                update = { it.loadDataWithBaseURL(pageUrl, Reader.page(article, dark, size), "text/html", "utf-8", null) })
        }
    }
}
