package io.github.kyotobyte.cdmplus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.kyotobyte.cdmplus.R

object Px {
    /** AMOLED: pure black background. Read from settings at start and on change. */
    var amoled by androidx.compose.runtime.mutableStateOf(false)
    /** Light theme: paper background, ink text, same pixel style. */
    var light by androidx.compose.runtime.mutableStateOf(false)
    val Bg: Color get() = if (light) Color(0xFFF4F1EA) else if (amoled) Color.Black else Color(0xFF0C0D10)
    val Panel: Color get() = if (light) Color(0xFFFFFFFF) else if (amoled) Color(0xFF0A0A0A) else Color(0xFF15171C)
    val Panel2: Color get() = if (light) Color(0xFFE9E5DC) else Color(0xFF1D2027)
    val Line: Color get() = if (light) Color(0xFF1E1F24) else Color(0xFF2E323B)
    val Text: Color get() = if (light) Color(0xFF15161A) else Color(0xFFEDEDED)
    val Dim: Color get() = if (light) Color(0xFF5E636E) else Color(0xFF8A909C)
    val Red = Color(0xFFE5483D)
    val Amber = Color(0xFFFFB000)
}

data class AccentOption(val name: String, val color: Color)

/** Hex like "#FF8800" or "ff8800" -> 0xRRGGBB, or null. */
fun parseHexColor(s: String): Int? = s.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

val Accents = listOf(
    AccentOption("Mint", Color(0xFF39FF88)),
    AccentOption("Amber", Color(0xFFFFB000)),
    AccentOption("Cyan", Color(0xFF3BD8FF)),
    AccentOption("Magenta", Color(0xFFFF4FD8)),
    AccentOption("Coral", Color(0xFFFF6B5A)),
    AccentOption("Violet", Color(0xFFA78BFF)),
)

val LocalAccent = staticCompositionLocalOf { Accents[0].color }

val PixelFont = FontFamily(Font(R.font.press_start_2p))
val PixelSmall = FontFamily(Font(R.font.silkscreen))
val BodyFont = FontFamily.SansSerif

object Type {
    val H1 get() = TextStyle(fontFamily = PixelFont, fontSize = 15.sp, lineHeight = 22.sp, color = Px.Text)
    val H2 get() = TextStyle(fontFamily = PixelFont, fontSize = 11.sp, lineHeight = 16.sp, color = Px.Text)
    val Label get() = TextStyle(fontFamily = PixelSmall, fontSize = 13.sp, lineHeight = 15.sp, color = Px.Text)
    val Body get() = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 19.sp, color = Px.Text)
    val BodyStrong get() = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = Px.Text)
    val Small get() = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 16.sp, color = Px.Dim)
}

@Composable
fun CdmTheme(accentIndex: Int, customAccent: Int = -1, content: @Composable () -> Unit) {
    val base = if (customAccent >= 0) Color(0xFF000000 or customAccent.toLong()) else Accents.getOrElse(accentIndex) { Accents[0] }.color
    // On the light theme very bright accents are darkened a little so text on them stays readable.
    val accent = if (Px.light && (base.red * 0.3f + base.green * 0.59f + base.blue * 0.11f) > 0.62f) Color(base.red * 0.7f, base.green * 0.7f, base.blue * 0.7f) else base
    val scheme = (if (Px.light) androidx.compose.material3.lightColorScheme(primary = accent, onPrimary = Px.Bg, background = Px.Bg, surface = Px.Panel, onSurface = Px.Text, onBackground = Px.Text, outline = Px.Line, error = Px.Red) else darkColorScheme(
        primary = accent, onPrimary = Px.Bg, background = Px.Bg, surface = Px.Panel,
        onSurface = Px.Text, onBackground = Px.Text, outline = Px.Line, error = Px.Red,
    ))
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

fun formatBytes(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

fun formatEta(remaining: Long, bps: Long): String {
    if (bps <= 0 || remaining <= 0) return "--:--"
    val s = remaining / bps
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
