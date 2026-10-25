package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Calendar

/** Set to true to draw dialogs inline (used by screenshot tests). */
var InlineDialogs = false

@Composable
fun PixelDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val frame = @Composable {
        Column(Modifier.fillMaxWidth().background(Px.Panel).pixelBorder(LocalAccent.current).padding(18.dp)) {
            Text(title.uppercase(), style = Type.H2.copy(color = LocalAccent.current))
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
    if (InlineDialogs) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) { frame() }
    } else {
        Dialog(onDismissRequest = onDismiss) { frame() }
    }
}

@Composable
fun PixelField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minHeight: Int = 0) {
    val accent = LocalAccent.current
    Column(modifier.fillMaxWidth().pixelBorder(Px.Line).background(Px.Bg).padding(10.dp)) {
        Box0 {
            if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Px.Dim))
            BasicTextField(
                value, onChange, singleLine = singleLine, textStyle = Type.Body, cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
            )
        }
    }
}

@Composable
private fun Box0(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box { content() }

data class AddRequest(val urls: List<String>, val fileName: String?, val threads: Int, val scheduledAt: Long?, val speedLimitKb: Int = 0)

val UrlRegex = Regex("""(?:https?|ftp)://[^\s<>"']+|magnet:\?[^\s<>"']+|file:///[^\s<>"']+\.torrent""", RegexOption.IGNORE_CASE)
fun extractUrls(text: String): List<String> = UrlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()

/** Add one or many links, rename, choose threads, optionally schedule. */
@Composable
fun AddDownloadDialog(initialText: String, defaultThreads: Int, suggestedName: String? = null, onDismiss: () -> Unit, onConfirm: (AddRequest) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var name by remember { mutableStateOf(suggestedName ?: "") }
    var threads by remember { mutableIntStateOf(defaultThreads) }
    var schedule by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf((Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var minute by remember { mutableIntStateOf(0) }
    var limitSteps by remember { mutableIntStateOf(0) }
    val urls = extractUrls(text).flatMap { expandPattern(it) }.distinct()
    PixelDialog("New download", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("LINK(S) - one per line, or a pattern like img[001-120].jpg", style = Type.Label.copy(color = Px.Dim))
            Spacer(Modifier.height(6.dp))
            PixelField(text, { text = it }, "https://…", singleLine = false, minHeight = 56)
            if (urls.size <= 1) {
                Spacer(Modifier.height(12.dp))
                Text("FILE NAME (optional rename)", style = Type.Label.copy(color = Px.Dim))
                Spacer(Modifier.height(6.dp))
                PixelField(name, { name = it }, urls.firstOrNull()?.let { io.github.kyotobyte.cdmplus.engine.SegmentedDownloader.fileNameFromUrl(it) } ?: "auto")
            } else {
                Spacer(Modifier.height(8.dp))
                Text("${urls.size} links will be added to the queue", style = Type.Small.copy(color = LocalAccent.current))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("THREADS", style = Type.Label, modifier = Modifier.weight(1f))
                PixelStepper(threads, 1..32, { threads = it })
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SPEED LIMIT", style = Type.Label)
                    Text(if (limitSteps == 0) "Unlimited" else "${limitSteps * 256} KB/s for this file", style = Type.Small)
                }
                PixelStepper(limitSteps, 0..40, { limitSteps = it })
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("START LATER", style = Type.Label, modifier = Modifier.weight(1f))
                PixelSwitch(schedule) { schedule = it }
            }
            if (schedule) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelStepper(hour, 0..23, { hour = it }, "h")
                    PixelStepper(minute, 0..59, { minute = it }, "m")
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Cancel", onDismiss, filled = false, modifier = Modifier.weight(1f))
                PixelButton("Download", {
                    val at = if (schedule) Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
                    }.timeInMillis else null
                    onConfirm(AddRequest(urls, name.takeIf { it.isNotBlank() && urls.size == 1 }, threads, at, limitSteps * 256))
                }, enabled = urls.isNotEmpty(), modifier = Modifier.weight(1f), icon = PxIcons.Download)
            }
        }
    }
}
