package io.github.kyotobyte.cdmplus.ui

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView

/** Browser helpers: media sniffing, page-link grabbing, desktop mode, save as PDF. */
object MediaSniffer {
    private val mediaExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "m3u8", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val fileExt = mediaExt + setOf("zip", "rar", "7z", "apk", "pdf", "iso", "exe", "dmg", "jpg", "jpeg", "png", "gif", "webp", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "tar", "gz", "torrent", "srt", "vtt")
    /** Excluded on purpose: YouTube/Google video and DRM streams. */
    private val excludedHosts = listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "netflix.com", "nflxvideo.net", "primevideo.com", "hotstar.com", "disneyplus.com")

    fun ext(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun excluded(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return excludedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isMedia(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (excluded(url)) return false
        val e = ext(url)
        if (e == "ts" || e == "m4s") return false // HLS/DASH pieces: the playlist is caught instead
        return e in mediaExt
    }

    fun looksLikeFile(url: String) = ext(url) in fileExt && !excluded(url)

    fun label(url: String): String {
        val e = ext(url).uppercase()
        val name = url.substringBefore('?').substringAfterLast('/')
        return if (e == "M3U8") "HLS stream · $name" else "$e · $name"
    }

    const val GRAB_JS = "(function(){var s=new Set();document.querySelectorAll('a[href],video[src],audio[src],source[src],img[src]').forEach(function(e){var u=e.href||e.src;if(u&&u.indexOf('http')==0)s.add(u)});return JSON.stringify(Array.from(s));})()"

    /** evaluateJavascript returns a JSON string literal holding our JSON array. */
    fun parseGrab(raw: String?, pageUrl: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return runCatching {
            val inner = kotlinx.serialization.json.Json.decodeFromString<String>(raw)
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(inner)
        }.getOrDefault(emptyList())
            .filter { it != pageUrl && !excluded(it) }
            .distinct()
            .sortedByDescending { looksLikeFile(it) }
    }

    private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    fun applyDesktop(wv: WebView, on: Boolean, mobileUa: String? = null) {
        wv.settings.userAgentString = if (on) DESKTOP_UA else mobileUa
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
    }

    /** Android's own print dialog, where "Save as PDF" is the target. */
    fun savePdf(ctx: Context, wv: WebView) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val name = (wv.title?.takeIf { it.isNotBlank() } ?: "page").take(60)
        pm.print(name, wv.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }
}
