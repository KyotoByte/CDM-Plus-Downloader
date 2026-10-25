package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import kotlin.coroutines.coroutineContext

/**
 * Link grabber with crawl depth: starts at a page, follows links up to [depth] levels
 * (1 = just this page), optionally staying on the same site, and collects links to files
 * whose extension matches the filter.
 */
class LinkCrawler(private val client: OkHttpClient) {
    data class Found(val url: String, val name: String, val ext: String, val fromPage: String)
    data class Options(val depth: Int = 1, val sameSite: Boolean = true, val exts: Set<String> = DEFAULT_EXTS, val maxPages: Int = 150, val headers: Map<String, String> = emptyMap())

    companion object {
        val DEFAULT_EXTS = setOf("mp4", "mkv", "webm", "mp3", "m4a", "flac", "zip", "rar", "7z", "apk", "pdf", "jpg", "jpeg", "png", "gif", "iso", "exe", "torrent")
        val PRESETS = linkedMapOf(
            "Video" to setOf("mp4", "mkv", "webm", "avi", "mov", "m3u8"),
            "Music" to setOf("mp3", "m4a", "flac", "wav", "ogg", "opus"),
            "Images" to setOf("jpg", "jpeg", "png", "gif", "webp"),
            "Docs" to setOf("pdf", "epub", "doc", "docx", "txt"),
            "Archives" to setOf("zip", "rar", "7z", "tar", "gz", "iso"),
            "Apps" to setOf("apk", "xapk", "exe"),
        )
        fun site(host: String): String { val p = host.split('.'); return if (p.size >= 2) p.takeLast(2).joinToString(".") else host }
        fun extOf(url: String): String = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.', "")?.lowercase() ?: ""
    }

    suspend fun crawl(start: String, o: Options, onPage: (pages: Int, found: Int) -> Unit = { _, _ -> }): List<Found> = withContext(Dispatchers.IO) {
        val startUrl = start.toHttpUrlOrNull() ?: return@withContext emptyList()
        val home = site(startUrl.host)
        val seen = HashSet<String>(); val found = LinkedHashMap<String, Found>()
        var frontier = listOf(startUrl.toString()); seen += startUrl.toString()
        var pages = 0
        for (level in 1..o.depth.coerceIn(1, 3)) {
            val next = ArrayList<String>()
            for (page in frontier) {
                coroutineContext.ensureActive()
                if (pages >= o.maxPages) break
                val html = runCatching { fetchHtml(page, o.headers) }.getOrNull() ?: continue
                pages++
                val doc = Jsoup.parse(html, page)
                val links = doc.select("a[href], source[src], video[src], audio[src], img[src], link[href]").map { e -> if (e.hasAttr("href")) e.absUrl("href") else e.absUrl("src") }
                for (l in links) {
                    val u = l.toHttpUrlOrNull() ?: continue
                    val clean = u.newBuilder().fragment(null).build().toString()
                    val ext = extOf(clean)
                    if (ext.isNotEmpty() && ext in o.exts) {
                        if (o.sameSite && site(u.host) != home && false) continue // files may live on a CDN: always keep them
                        found.getOrPut(clean) { Found(clean, u.pathSegments.lastOrNull() ?: clean, ext, page) }
                    } else if (level < o.depth && (ext.isEmpty() || ext in setOf("html", "htm", "php", "asp", "aspx", "jsp"))) {
                        if (o.sameSite && site(u.host) != home) continue
                        if (seen.add(clean)) next += clean
                    }
                }
                onPage(pages, found.size)
            }
            frontier = next
            if (frontier.isEmpty()) break
        }
        found.values.toList()
    }

    private fun fetchHtml(url: String, headers: Map<String, String>): String? {
        val b = Request.Builder().url(url).header("User-Agent", SegmentedDownloader.DEFAULT_UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val type = r.header("Content-Type") ?: ""
            if (!type.contains("html", true)) return null
            val body = r.body ?: return null
            if ((body.contentLength()) > 5_000_000) return null
            return body.string()
        }
    }
}
