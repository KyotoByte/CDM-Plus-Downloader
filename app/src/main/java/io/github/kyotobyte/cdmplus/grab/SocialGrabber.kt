package io.github.kyotobyte.cdmplus.grab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One downloadable choice (a quality of a video, an image size, an audio track). */
data class GrabOption(
    val url: String,
    val kind: String, // "video", "image", "audio"
    val label: String,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = -1,
    val ext: String = "mp4",
    val audioUrl: String? = null, // video-only stream that needs this audio merged in
    val mime: String? = null,
    /** Engine step after download (e.g. "mega:<key>" = decrypt). */
    val postProcess: String? = null,
    /** Exact file name to save as (MEGA keeps the uploader's name). */
    val fileName: String? = null,
)

/** One entry of a post (a carousel slide, or the single video) with its quality choices, best first. */
/** One file inside a shared folder (e.g. MEGA): [path] is its folder inside the share ("" = top), [link] resolves it. */
data class FolderFile(val path: String, val name: String, val bytes: Long, val link: String)

data class GrabItem(val index: Int, val options: List<GrabOption>, val thumb: String? = null)

data class GrabResult(
    val platform: String,
    val title: String,
    val author: String? = null,
    val items: List<GrabItem> = emptyList(),
    /** Name for "Extract music", e.g. "Original audio - @user" or the song name. */
    val musicTitle: String? = null,
    /** A direct audio file when the site has one (TikTok sounds). */
    val musicUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val playlist: List<Pair<String, String>> = emptyList(), // (title, url) for playlists
    val subtitles: List<GrabOption> = emptyList(),
    /** Shared folder contents (MEGA folder links): shown as a pick list, each file resolved when downloaded. */
    val folderFiles: List<FolderFile> = emptyList(),
)

/** Media URLs the in-app browser caught on the page the user tapped GRAB on. */
object SniffedMedia {
    @Volatile private var page: String? = null
    @Volatile private var urls: List<String> = emptyList()
    fun set(pageUrl: String, media: List<String>) { page = pageUrl; urls = media }
    fun forPage(pageUrl: String): List<String> = if (page == pageUrl) urls else emptyList()
}

class GrabException(msg: String, val details: String? = null) : IOException(msg)

/**
 * Instagram + TikTok grabber for PUBLIC posts. It reads only what the post's own page or its public
 * embed page loads (embedded JSON / og tags), the same data a browser gets, or the HTML of the page the
 * user opened in CDM+'s browser (logged in as themselves). No private APIs, no signing secrets,
 * no private accounts.
 */
class SocialGrabber(base: OkHttpClient) {
    private val jar = MemoryJar()
    private val client = base.newBuilder().cookieJar(jar).followRedirects(true).build()

    companion object {
        const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val IG = Regex("""instagram\.com/(?:[\w.]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
        private val TT = Regex("""(?:tiktok\.com/(@[\w.-]+/(?:video|photo)/\d+|t/\w+|v/\d+)|vm\.tiktok\.com/\w+|vt\.tiktok\.com/\w+)""")

        fun platformOf(url: String): String? = when {
            IG.containsMatchIn(url) -> "Instagram"
            TT.containsMatchIn(url) -> "TikTok"
            XfbGrabber.isX(url) -> "X"
            XfbGrabber.isFacebook(url) -> "Facebook"
            else -> null
        }

        fun isSupported(url: String) = platformOf(url) != null

        /** Undo one level of JS/JSON string escaping over a whole text (used to dig nested JSON out of pages). */
        fun unescapeLayer(s: String): String {
            val sb = StringBuilder(s.length); var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        '"', '\\', '/' -> { sb.append(n); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'u' -> if (i + 5 < s.length) { val h = s.substring(i + 2, i + 6).toIntOrNull(16); if (h != null) { sb.append(h.toChar()); i += 6 } else { sb.append(c); i++ } } else { sb.append(c); i++ }
                        else -> { sb.append(c); i++ }
                    }
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }

        /** The JSON value after `"key":` at [from], cut out by matching brackets (strings respected). */
        fun valueAfter(text: String, key: String, from: Int = 0): Pair<JsonElement, Int>? {
            var idx = text.indexOf("\"$key\"", from)
            while (idx >= 0) {
                var p = idx + key.length + 2
                while (p < text.length && text[p].isWhitespace()) p++
                if (p < text.length && text[p] == ':') {
                    p++; while (p < text.length && text[p].isWhitespace()) p++
                    val end = matchEnd(text, p)
                    if (end > p) {
                        val el = runCatching { json.parseToJsonElement(text.substring(p, end)) }.getOrNull()
                        if (el != null && el !is JsonNull) return el to end
                    }
                }
                idx = text.indexOf("\"$key\"", idx + 1)
            }
            return null
        }

        private fun matchEnd(t: String, start: Int): Int {
            val open = t.getOrNull(start) ?: return -1
            if (open != '{' && open != '[') return -1
            var depth = 0; var i = start; var inStr = false
            while (i < t.length) {
                val c = t[i]
                if (inStr) { if (c == '\\') i++ else if (c == '"') inStr = false }
                else when (c) { '"' -> inStr = true; '{', '[' -> depth++; '}', ']' -> { depth--; if (depth == 0) return i + 1 } }
                i++
            }
            return -1
        }

        private fun layers(html: String): Sequence<String> = generateSequence(html) { prev -> unescapeLayer(prev).takeIf { it != prev } }.take(4)

        private val JsonElement?.obj get() = this as? JsonObject
        private val JsonElement?.arr get() = this as? JsonArray
        private fun JsonElement?.str(k: String) = (this as? JsonObject)?.get(k)?.let { (it as? JsonPrimitive)?.contentOrNull }
        private fun JsonElement?.int(k: String) = str(k)?.toDoubleOrNull()?.toInt() ?: 0
        private fun JsonElement?.long(k: String) = str(k)?.toDoubleOrNull()?.toLong() ?: -1L
        private fun JsonElement?.bool(k: String) = str(k) == "true"
        private fun JsonElement?.o(k: String) = (this as? JsonObject)?.get(k) as? JsonObject
        private fun JsonElement?.a(k: String) = (this as? JsonObject)?.get(k) as? JsonArray

        private fun meta(html: String, prop: String): String? =
            Regex("""<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$prop["']""").find(html)?.groupValues?.get(1)

        private fun htmlDecode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

        // ---------------- Instagram ----------------
        private fun igGraphNode(n: JsonObject, idx: Int): GrabItem {
            val opts = ArrayList<GrabOption>()
            if (n.bool("is_video") && n.str("video_url") != null) {
                opts += GrabOption(n.str("video_url")!!, "video", "Video ${n.o("dimensions").int("height").takeIf { it > 0 }?.let { "${it}p" } ?: ""}".trim(), n.o("dimensions").int("width"), n.o("dimensions").int("height"))
            }
            n.a("display_resources")?.mapNotNull { it.obj }?.sortedByDescending { it.int("config_width") }?.take(if (opts.isEmpty()) 3 else 1)?.forEach {
                opts += GrabOption(it.str("src") ?: return@forEach, "image", "Photo ${it.int("config_width")}x${it.int("config_height")}", it.int("config_width"), it.int("config_height"), ext = "jpg")
            }
            if (opts.none { it.kind == "image" }) n.str("display_url")?.let { opts += GrabOption(it, "image", "Photo", ext = "jpg") }
            return GrabItem(idx, opts, n.str("display_url"))
        }

        private fun igV1Node(n: JsonObject, idx: Int): GrabItem {
            val opts = ArrayList<GrabOption>()
            n.a("video_versions")?.mapNotNull { it.obj }?.distinctBy { it.str("url")?.substringBefore('?') + it.int("width") }?.sortedByDescending { it.int("width") * it.int("height") }?.forEach {
                opts += GrabOption(it.str("url") ?: return@forEach, "video", "Video ${it.int("height")}p", it.int("width"), it.int("height"))
            }
            n.o("image_versions2").a("candidates")?.mapNotNull { it.obj }?.sortedByDescending { it.int("width") }?.take(if (opts.isEmpty()) 3 else 1)?.forEach {
                opts += GrabOption(it.str("url") ?: return@forEach, "image", "Photo ${it.int("width")}x${it.int("height")}", it.int("width"), it.int("height"), ext = "jpg")
            }
            return GrabItem(idx, opts, n.o("image_versions2").a("candidates")?.firstOrNull().str("url"))
        }

        fun parseInstagram(html: String): GrabResult? {
            for (t in layers(html)) {
                // Public embed / old web page: GraphQL "shortcode_media"
                (valueAfter(t, "shortcode_media") ?: valueAfter(t, "xdt_shortcode_media"))?.first?.obj?.let { m ->
                    val kids = m.o("edge_sidecar_to_children").a("edges")?.mapNotNull { it.obj?.o("node") }
                    val items = if (!kids.isNullOrEmpty()) kids.mapIndexed { i, k -> igGraphNode(k, i) } else listOf(igGraphNode(m, 0))
                    val mus = m.o("clips_music_attribution_info")
                    val owner = m.o("owner").str("username")
                    val music = mus?.let { if (it.bool("uses_original_audio") || it.str("song_name").isNullOrBlank()) "Original audio - @${it.str("artist_name") ?: owner}" else "${it.str("song_name")} - ${it.str("artist_name")}" }
                    val caption = m.o("edge_media_to_caption").a("edges")?.firstOrNull()?.obj?.o("node").str("text")
                    // A reel whose video link Instagram withheld (happens logged out): photos alone are not an answer.
                    val videoWithheld = (m.bool("is_video") || m.str("product_type") == "clips") && kids.isNullOrEmpty() && items.none { i -> i.options.any { it.kind == "video" } }
                    if (!videoWithheld && items.any { it.options.isNotEmpty() }) return GrabResult("Instagram", caption?.take(60) ?: "Instagram post", owner, items, music ?: owner?.let { "Original audio - @$it" })
                }
                // Logged-in web page: v1 "items" with video_versions / carousel_media
                val v1 = valueAfter(t, "xdt_api__v1__media__shortcode__web_info")?.first.a("items")?.firstOrNull()?.obj
                    ?: valueAfter(t, "items")?.first?.arr?.firstOrNull { it.obj?.containsKey("video_versions") == true || it.obj?.containsKey("carousel_media") == true || it.obj?.containsKey("image_versions2") == true }?.obj
                if (v1 != null) {
                    val car = v1.a("carousel_media")?.mapNotNull { it.obj }
                    val items = if (!car.isNullOrEmpty()) car.mapIndexed { i, c -> igV1Node(c, i) } else listOf(igV1Node(v1, 0))
                    val user = v1.o("user").str("username") ?: v1.o("owner").str("username")
                    val mi = v1.o("clips_metadata").o("music_info").o("music_asset_info")
                    val os = v1.o("clips_metadata").o("original_sound_info")
                    val music = when {
                        mi != null && !mi.str("title").isNullOrBlank() -> "${mi.str("title")} - ${mi.str("display_artist")}"
                        os != null -> os.str("original_audio_title")?.takeIf { it.isNotBlank() && it != "Original audio" }?.let { "$it - @${os.o("ig_artist").str("username") ?: user}" } ?: "Original audio - @${os.o("ig_artist").str("username") ?: user}"
                        else -> user?.let { "Original audio - @$it" }
                    }
                    if (items.any { it.options.isNotEmpty() }) return GrabResult("Instagram", v1.o("caption").str("text")?.take(60) ?: "Instagram post", user, items, music)
                }
            }
            return null
        }

        // ---------------- TikTok ----------------
        fun parseTikTok(html: String): GrabResult? {
            for (t in layers(html)) {
                val it0 = valueAfter(t, "itemStruct")?.first?.obj ?: continue
                val v = it0.o("video")
                val opts = ArrayList<GrabOption>()
                v.a("bitrateInfo")?.mapNotNull { it.obj }?.forEach { b ->
                    val pa = b.o("PlayAddr")
                    val url = pa.a("UrlList")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull() ?: return@forEach
                    val h = pa.int("Height"); val w = pa.int("Width")
                    val codec = if (b.str("CodecType")?.contains("265") == true || b.str("GearName")?.contains("bytevc1") == true) "HEVC" else "H.264"
                    opts += GrabOption(url, "video", "${minOf(w, h).takeIf { it > 0 } ?: h}p $codec · ${b.long("Bitrate") / 1000} kbps", w, h, pa.long("DataSize"))
                }
                opts.sortByDescending { it.width.toLong() * it.height * 10_000 + (it.bytes.coerceAtLeast(0) / 1000) }
                v.str("playAddr")?.takeIf { it.isNotBlank() }?.let { if (opts.none { o -> o.url == it }) opts += GrabOption(it, "video", "Standard ${minOf(v.int("width"), v.int("height"))}p", v.int("width"), v.int("height")) }
                v.str("downloadAddr")?.takeIf { it.isNotBlank() }?.let { opts += GrabOption(it, "video", "With watermark", v.int("width"), v.int("height")) }
                val items = ArrayList<GrabItem>()
                val images = it0.o("imagePost").a("images")?.mapNotNull { it.obj }
                if (!images.isNullOrEmpty()) {
                    images.forEachIndexed { i, im ->
                        val url = im.o("imageURL").a("urlList")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull() ?: return@forEachIndexed
                        items += GrabItem(i, listOf(GrabOption(url, "image", "Photo ${im.int("imageWidth")}x${im.int("imageHeight")}", im.int("imageWidth"), im.int("imageHeight"), ext = if (url.contains(".webp")) "webp" else "jpg")), url)
                    }
                } else if (opts.isNotEmpty()) items += GrabItem(0, opts, v.str("cover"))
                val mus = it0.o("music")
                val author = it0.o("author").str("uniqueId")
                val musicTitle = mus?.let { m -> m.str("title")?.let { tt -> if (tt.startsWith("original sound", true)) "Original audio - @${m.str("authorName") ?: author}" else "$tt - ${m.str("authorName") ?: ""}".trimEnd(' ', '-') } }
                if (items.isEmpty() && mus?.str("playUrl") == null) continue
                return GrabResult("TikTok", it0.str("desc")?.takeIf { it.isNotBlank() }?.take(60) ?: "TikTok by @$author", author, items, musicTitle, mus?.str("playUrl")?.takeIf { it.isNotBlank() })
            }
            return null
        }

        /** Last resort for any page: og:video / og:image. */
        fun parseOg(platform: String, html: String): GrabResult? {
            val vid = (meta(html, "og:video:secure_url") ?: meta(html, "og:video"))?.let(::htmlDecode)
            val img = meta(html, "og:image")?.let(::htmlDecode)
            val opts = listOfNotNull(vid?.let { GrabOption(it, "video", "Video") }, img?.let { GrabOption(it, "image", "Cover photo", ext = "jpg") })
            if (opts.isEmpty()) return null
            return GrabResult(platform, meta(html, "og:title")?.let(::htmlDecode)?.take(60) ?: "$platform post", null, listOf(GrabItem(0, opts, img)))
        }

        /** Parses a page's HTML (fetched, or taken from the in-app browser). */
        fun parseHtml(pageUrl: String, html: String): GrabResult? {
            if (looksDrm(html)) throw GrabException("Protected content, can't be downloaded")
            return when (platformOf(pageUrl)) {
                "Instagram" -> parseInstagram(html)
                "TikTok" -> parseTikTok(html)
                "Facebook" -> XfbGrabber.parseFacebook(html, XfbGrabber.fbVideoId(pageUrl))
                else -> null
            } ?: parseOg(platformOf(pageUrl) ?: "Page", html)?.takeIf { r -> r.items.any { i -> i.options.any { it.kind == "video" } } || (platformOf(pageUrl) != null && platformOf(pageUrl) != "X" && platformOf(pageUrl) != "Facebook") }
        }


        // ---------------- Media the in-app browser caught while the page played ----------------
        private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        /** Lenient base64 / base64url decode (works on Android 7, no java.util.Base64 needed). */
        fun b64(s: String): String {
            val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
            for (c0 in s) {
                val c = when (c0) { '-' -> '+'; '_' -> '/'; else -> c0 }
                val v = B64.indexOf(c); if (v < 0) continue
                buf = (buf shl 6) or v; bits += 6
                if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
            }
            return out.toString("UTF-8")
        }

        fun queryParam(url: String, key: String): String? = url.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

        /** Drops the byte-range params a streaming player adds, so the URL is the whole file. */
        fun stripRange(url: String): String {
            val q = url.substringAfter('?', ""); if (q.isEmpty()) return url
            val kept = q.split('&').filter { val k = it.substringBefore('='); k != "bytestart" && k != "byteend" }
            return url.substringBefore('?') + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }

        /**
         * Builds the video choice from what the browser caught while an Instagram reel played
         * (its DASH video + audio files, or a progressive MP4). Best quality first; video-only
         * files get the audio track attached so CDM+ joins them.
         */
        fun fromSniffed(platform: String, urls: List<String>, kinds: Map<String, String> = emptyMap()): GrabResult? {
            if (platform != "Instagram" && platform != "Facebook") return null
            class S(val url: String, val audio: Boolean, val progressive: Boolean, val bitrate: Long, val order: Int, val asset: String?)
            val seen = LinkedHashMap<String, S>()
            urls.forEachIndexed { i, raw ->
                val c = igMediaKey(raw) ?: return@forEachIndexed
                val clean = stripRange(raw)
                if (c in seen) return@forEachIndexed
                val efg = queryParam(raw, "efg")?.let(::b64) ?: ""
                val tag = Regex("\"vencode_tag\"\\s*:\\s*\"([^\"]+)\"").find(efg)?.groupValues?.get(1) ?: ""
                val br = Regex("\"bitrate\"\\s*:\\s*(\\d+)").find(efg)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val asset = Regex("\"xpv_asset_id\"\\s*:\\s*(\\d+)").find(efg)?.groupValues?.get(1)
                // What the file really is (probed from its first bytes) beats what the URL says.
                val probed = kinds[c]
                if (probed == "none") return@forEachIndexed
                val audio = if (probed != null) probed == "soun" else tag.contains("audio", true)
                val progressive = if (probed != null) probed == "both" else tag.contains("progressive", true)
                seen[c] = S(clean, audio, progressive, br, i, asset)
            }
            if (seen.isEmpty()) return null
            // A reel page can also load other reels: stay with the reel whose video showed up first.
            val firstVideo = seen.values.filter { !it.audio }.minByOrNull { it.order } ?: return null
            val same = seen.values.filter { firstVideo.asset == null || it.asset == null || it.asset == firstVideo.asset }
            val audio = same.filter { it.audio }.maxByOrNull { it.bitrate }
            val vids = same.filter { !it.audio }.sortedWith(compareByDescending<S> { it.progressive }.thenByDescending { it.bitrate }.thenBy { it.order })
            if (vids.isEmpty()) return null
            val opts = ArrayList<GrabOption>()
            vids.forEachIndexed { n, v ->
                val q = if (n == 0) "best" else "smaller"
                opts += when {
                    v.progressive -> GrabOption(v.url, "video", "Video ($q, with sound)")
                    audio != null -> GrabOption(v.url, "video", "Video ($q, with sound)", audioUrl = audio.url)
                    else -> GrabOption(v.url, "video", "Video ($q, no sound caught)")
                }
            }
            audio?.let { opts += GrabOption(it.url, "audio", "Audio only", ext = "m4a") }
            return if (platform == "Facebook") GrabResult(platform, "Facebook video", null, listOf(GrabItem(0, opts)),
                headers = mapOf("User-Agent" to XfbGrabber.DESKTOP_UA, "Referer" to "https://www.facebook.com/"))
            else GrabResult(platform, "Instagram reel", null, listOf(GrabItem(0, opts)),
                headers = mapOf("Referer" to "https://www.instagram.com/"))
        }

        /** Same file regardless of byte range = same key. Null for anything that isn't an Instagram CDN .mp4. */
        fun igMediaKey(raw: String): String? {
            val host = runCatching { java.net.URI(raw).host }.getOrNull() ?: return null
            if (!(host.endsWith("cdninstagram.com") || host.endsWith("fbcdn.net"))) return null
            if (!raw.substringBefore('?').endsWith(".mp4", true)) return null
            return stripRange(raw).substringBefore('?')
        }

        /** True when the sniffed list has a video with sound (progressive, or a video + an audio part). */
        fun hasSound(r: GrabResult?): Boolean = r?.items?.firstOrNull()?.options?.firstOrNull { it.kind == "video" }?.let { it.audioUrl != null || it.label.contains("with sound") } == true

        private val probeClient by lazy { OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

        /** Reads the first 32 KB of a media URL and says what it holds: "vide", "soun", "both" or "none" (null = unknown). */
        fun probeKind(url: String): String? = runCatching {
            val req = Request.Builder().url(url).header("Range", "bytes=0-32767").header("User-Agent", MOBILE_UA).header("Referer", "https://www.instagram.com/").build()
            probeClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val src = r.body?.source() ?: return null
                src.request(32768); val bytes = src.buffer.readByteArray(minOf(32768L, src.buffer.size))
                val h = io.github.kyotobyte.cdmplus.engine.Mp4Joiner.handlersIn(bytes)
                when { "vide" in h && "soun" in h -> "both"; "vide" in h -> "vide"; "soun" in h -> "soun"; h.isEmpty() -> null; else -> "none" }
            }
        }.getOrNull()

        /** [fromSniffed] after probing every candidate file, so audio is never missed or mistaken for video. */
        suspend fun fromSniffedProbed(platform: String, urls: List<String>): GrabResult? = withContext(Dispatchers.IO) {
            if (platform != "Instagram" && platform != "Facebook") return@withContext null
            val firstByKey = LinkedHashMap<String, String>()
            urls.forEach { u -> igMediaKey(u)?.let { k -> firstByKey.getOrPut(k) { u } } }
            val kinds = kotlinx.coroutines.coroutineScope {
                firstByKey.entries.take(16).map { (k, u) -> async { k to probeKind(stripRange(u)) } }.map { it.await() }
            }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
            fromSniffed(platform, urls, kinds) ?: fromSniffed(platform, urls)
        }

        /** Instagram /reel/, /reels/, /tv/ and TikTok /video/ links are videos: a cover alone is not an answer. */
        fun isVideoPost(url: String): Boolean = Regex("""instagram\.com/(?:[\w.]+/)?(reel|reels|tv)/""").containsMatchIn(url) || Regex("""tiktok\.com/@[^/]+/video/""").containsMatchIn(url) ||
            (XfbGrabber.isFacebook(url) && Regex("""(?:fb\.watch/|/reels?/|/videos/|/watch|/share/[rv]/)""").containsMatchIn(url))

        private fun hasVideo(r: GrabResult?) = r?.items?.any { i -> i.options.any { it.kind == "video" } } == true

        /**
         * GRAB from the in-app browser: the live page first, then what the browser caught while the
         * video played, then a fresh fetch. Never answers a reel with only a cover photo.
         */
        suspend fun fromPage(pageUrl: String, html: String?, sniffed: List<String>, fetch: suspend () -> GrabResult): GrabResult {
            val platform = platformOf(pageUrl)
            val wantsVideo = isVideoPost(pageUrl)
            // X / Facebook: the public extractor gives every quality with sound; the live page is the fallback.
            if (platform == "X" || platform == "Facebook") {
                val first = runCatching { fetch() }
                first.getOrNull()?.let { if (hasVideo(it)) return it }
                html?.let { h -> runCatching { parseHtml(pageUrl, h) }.getOrNull() }?.let { if (hasVideo(it)) return it }
                if (platform == "Facebook") fromSniffedProbed(platform, sniffed)?.let { return it }
                first.getOrNull()?.let { return it }
                throw first.exceptionOrNull() ?: GrabException("Couldn't find the video in this $platform post")
            }
            val page = html?.let { parseHtml(pageUrl, it) }
            if (page != null && (hasVideo(page) || !wantsVideo)) return page
            val sn = platform?.let { fromSniffedProbed(it, sniffed) }
            if (sn != null) return sn.copy(title = page?.title ?: sn.title, author = page?.author, musicTitle = page?.musicTitle,
                items = sn.items.map { it.copy(thumb = page?.items?.firstOrNull()?.thumb) })
            val fetched = runCatching { fetch() }
            fetched.getOrNull()?.let { if (hasVideo(it) || !wantsVideo) return it }
            if (wantsVideo) throw GrabException(
                "Couldn't find the video in this ${platform ?: "post"}. Let it play for 2-3 seconds in the CDM+ browser, then tap GRAB again. Logging in to Instagram in the browser also helps.",
                fetched.exceptionOrNull()?.message)
            return page ?: fetched.getOrThrow()
        }

        fun looksDrm(html: String): Boolean = html.contains("com.widevine.alpha") || html.contains("com.microsoft.playready") || html.contains("com.apple.fps")
    }

    /** Fetches a public post and returns its media. Throws [GrabException] with a clear message when it can't. */
    suspend fun grab(url: String): GrabResult = withContext(Dispatchers.IO) {
        val platform = platformOf(url) ?: throw GrabException("Not an Instagram, TikTok, X or Facebook link")
        when (platform) {
            "X" -> XfbGrabber.grabX(client, url)
            "Facebook" -> XfbGrabber.grabFacebook(client, url)
            "Instagram" -> {
                val (kind, code) = IG.find(url)!!.destructured
                val tries = listOf(
                    "https://www.instagram.com/p/$code/embed/captioned/" to MOBILE_UA,
                    "https://www.instagram.com/$kind/$code/" to MOBILE_UA,
                    "https://www.instagram.com/p/$code/embed/" to DESKTOP_UA,
                )
                for ((u, ua) in tries) {
                    val html = runCatching { fetch(u, ua) }.getOrNull() ?: continue
                    val r = parseInstagram(html)
                    if (r != null) return@withContext r.copy(headers = mapOf("User-Agent" to ua, "Referer" to "https://www.instagram.com/"))
                }
                // Public post data endpoint the Instagram web app itself uses when logged out.
                runCatching { igGraphql(code) }.getOrNull()?.let { parseInstagram(it) }?.let {
                    return@withContext it.copy(headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.instagram.com/"))
                }
                throw GrabException("Instagram didn't give out this video without a login (private, age-restricted, or a reel Instagram only plays in its app/site). Open it in the CDM+ browser, let it play 2-3 seconds, then tap GRAB. Logging in to Instagram there helps too.")
            }
            else -> {
                // Photo posts (/photo/<id>) only carry their data on the /video/<id> address.
                val html = fetch(url.replace(Regex("""(tiktok\.com/@[^/]+)/photo/"""), "$1/video/"), DESKTOP_UA)
                val r = parseTikTok(html) ?: throw GrabException("TikTok didn't return this video (private, removed, or TikTok changed its page). Try opening it in the CDM+ browser and tap Grab.")
                // TikTok's video links only work with the cookies from the same page visit.
                val host = okhttp3.HttpUrl.Builder().scheme("https").host("www.tiktok.com").build()
                val cookies = jar.all().filter { it.domain.endsWith("tiktok.com") }.joinToString("; ") { "${it.name}=${it.value}" }
                r.copy(headers = buildMap { put("User-Agent", DESKTOP_UA); put("Referer", "https://www.tiktok.com/"); if (cookies.isNotEmpty()) put("Cookie", cookies) }).also { host.toString() }
            }
        }
    }

    private fun igGraphql(code: String): String {
        val lsd = "AVqbxe3J_YA"
        val vars = "{\"shortcode\":\"$code\",\"fetch_tagged_user_count\":null,\"hoisted_comment_id\":null,\"hoisted_reply_id\":null}"
        val body = okhttp3.FormBody.Builder().add("variables", vars).add("doc_id", "8845758582119845").add("lsd", lsd).build()
        val req = Request.Builder().url("https://www.instagram.com/graphql/query").post(body)
            .header("User-Agent", DESKTOP_UA).header("X-IG-App-ID", "936619743392459").header("X-FB-LSD", lsd)
            .header("X-ASBD-ID", "129477").header("Sec-Fetch-Site", "same-origin").header("Referer", "https://www.instagram.com/reel/$code/").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw GrabException("Instagram answered ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    private fun fetch(url: String, ua: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw GrabException("The site answered ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    private class MemoryJar : CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookies.forEach { store[it.domain + "|" + it.name] = it } }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.filter { it.matches(url) }
        fun all(): List<Cookie> = store.values.toList()
    }
}
