package io.github.kyotobyte.cdmplus.grab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Public X (Twitter) and Facebook videos (Patch 1).
 *  - X: the public tweet-data endpoint X's own embeds use. It lists progressive MP4s that already carry sound.
 *  - Facebook: the public post page (desktop) carries the video's progressive SD/HD files and its DASH manifest;
 *    DASH video-only qualities get the best audio attached so CDM+ joins them into one MP4 with sound.
 */
object XfbGrabber {
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val X_STATUS = Regex("""(?:^|[/.])(?:x|twitter|fxtwitter|vxtwitter|fixupx|fixvx)\.com/(?:[A-Za-z0-9_]+/status(?:es)?|i/(?:web/)?status|i/statuses)/(\d{5,25})""", RegexOption.IGNORE_CASE)
    private val X_SHORT = Regex("""(?:^|//)t\.co/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
    private val FB = Regex("""(?:^|//|\.)(?:facebook\.com|fb\.com|fb\.watch)/""", RegexOption.IGNORE_CASE)
    private val FB_VIDEOISH = Regex("""(?:fb\.watch/|/reel/|/reels/|/videos/|/watch|/share/(?:r|v|p)/|/posts/|/story\.php|/permalink\.php|video\.php|/groups/[^/]+/(?:posts|permalink)/)""", RegexOption.IGNORE_CASE)

    fun isX(url: String) = X_STATUS.containsMatchIn(url) || X_SHORT.containsMatchIn(url)
    fun isFacebook(url: String) = FB.containsMatchIn(url) && FB_VIDEOISH.containsMatchIn(url)
    fun xStatusId(url: String): String? = X_STATUS.find(url)?.groupValues?.get(1)

    // ---------------------------------------------------------------- X
    /** Same token X's embed script sends: (id / 1e15 * PI) in base 36, without zeros and the dot. */
    fun xToken(id: String): String {
        val n = id.toDouble() / 1e15 * Math.PI
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var ip = n.toLong(); var frac = n - ip
        val sb = StringBuilder(); if (ip == 0L) sb.append('0'); while (ip > 0) { sb.insert(0, digits[(ip % 36).toInt()]); ip /= 36 }
        sb.append('.'); repeat(11) { frac *= 36; val d = frac.toInt(); sb.append(digits[d]); frac -= d }
        return sb.toString().replace("0", "").replace(".", "")
    }

    private fun JsonElement?.str(k: String) = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.o(k: String) = (this as? JsonObject)?.get(k) as? JsonObject
    private fun JsonElement?.a(k: String) = (this as? JsonObject)?.get(k) as? JsonArray

    private fun get(client: OkHttpClient, url: String, headers: Map<String, String>): Pair<Int, Pair<String, String>> {
        val b = Request.Builder().url(url); headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r -> return r.code to (r.request.url.toString() to (r.body?.string() ?: "")) }
    }

    /** t.co short links answer with a redirect (or a tiny page that refreshes to the post). */
    fun resolveShort(client: OkHttpClient, url: String): String {
        if (xStatusId(url) != null) return url
        val (_, res) = runCatching { get(client, url, mapOf("User-Agent" to "curl/8.0")) }.getOrElse { throw GrabException("Couldn't open this X link", it.message) }
        val (final, body) = res
        if (xStatusId(final) != null) return final
        Regex("""(?i)URL=([^"'>\s]+)""").find(body)?.groupValues?.get(1)?.let { if (xStatusId(it) != null) return it }
        Regex("""https?://(?:x|twitter)\.com/[^"'\s<>]+/status/\d+""").find(body)?.value?.let { return it }
        throw GrabException("This link doesn't lead to an X post")
    }

    fun parseX(body: String): GrabResult? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        if (root.str("__typename") == "TweetTombstone") return null
        val tweets = listOfNotNull(root, root.o("quoted_tweet"))
        val items = ArrayList<GrabItem>()
        for (t in tweets) for (m in t.a("mediaDetails") ?: JsonArray(emptyList())) {
            val type = m.str("type")
            val thumb = m.str("media_url_https")
            if (type == "video" || type == "animated_gif") {
                val vars = m.o("video_info").a("variants")?.mapNotNull { v ->
                    val u = v.str("url") ?: return@mapNotNull null
                    if (v.str("content_type") != "video/mp4") return@mapNotNull null
                    val wh = Regex("""/(\d{2,5})x(\d{2,5})/""").find(u)?.destructured
                    Triple(u, v.str("bitrate")?.toLongOrNull() ?: 0L, wh?.let { (w, h) -> w.toInt() to h.toInt() } ?: (0 to 0))
                }?.sortedByDescending { it.second } ?: emptyList()
                if (vars.isEmpty()) continue
                val opts = vars.map { (u, _, wh) ->
                    val q = if (wh.second > 0) "${minOf(wh.first, wh.second)}p" else "Video"
                    GrabOption(u, "video", if (type == "animated_gif") "GIF as MP4 $q" else "$q (with sound)", wh.first, wh.second)
                }
                items += GrabItem(items.size, opts, thumb)
            } else if (type == "photo" && thumb != null) {
                items += GrabItem(items.size, listOf(GrabOption("$thumb?name=orig", "image", "Photo (original)", m.o("original_info").str("width")?.toIntOrNull() ?: 0, m.o("original_info").str("height")?.toIntOrNull() ?: 0, ext = "jpg")), thumb)
            }
        }
        if (items.isEmpty()) return null
        val text = (root.str("text") ?: "").replace(Regex("""https://t\.co/\S+"""), "").replace(Regex("""\s+"""), " ").trim()
        val user = root.o("user").str("screen_name")
        return GrabResult("X", text.take(60).ifBlank { user?.let { "@$it on X" } ?: "X post" }, user?.let { "@$it" }, items,
            headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://x.com/"))
    }

    fun grabX(client: OkHttpClient, url0: String): GrabResult {
        val url = resolveShort(client, url0)
        val id = xStatusId(url) ?: throw GrabException("Not an X post link")
        val (code, res) = runCatching {
            get(client, "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${xToken(id)}", mapOf("User-Agent" to DESKTOP_UA, "Accept" to "application/json"))
        }.getOrElse { throw GrabException("Couldn't reach X. Check your connection and try again.", it.message) }
        val r = if (code == 200) parseX(res.second) else null
        if (r != null) return r
        if (code == 200 && res.second.contains("mediaDetails").not() && res.second.contains("\"text\""))
            throw GrabException("This X post has no video or photo to download.")
        throw GrabException("X didn't give out this post without a login (private account, age-restricted, or deleted).", "tweet-result answered $code")
    }

    // ---------------------------------------------------------------- Facebook
    private val FB_ID_PATTERNS = listOf(
        Regex("""/reels?/(\d{6,})"""), Regex("""/videos/(?:[^/?#]+/)?(\d{6,})"""), Regex("""[?&]v=(\d{6,})"""),
        Regex("""/watch/?\?v=(\d{6,})"""), Regex("""video\.php\?v=(\d{6,})"""),
    )
    fun fbVideoId(url: String): String? = FB_ID_PATTERNS.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    /** Reads the JSON string literal that starts at [quote] (the opening ") and returns it decoded. */
    private fun jsonStringAt(text: String, quote: Int): String? {
        if (quote < 0 || quote >= text.length || text[quote] != '"') return null
        var i = quote + 1
        while (i < text.length) { when (text[i]) { '\\' -> i += 2; '"' -> return runCatching { json.decodeFromString<String>(text.substring(quote, i + 1)) }.getOrNull(); else -> i++ } }
        return null
    }
    private fun valueOf(text: String, key: String, from: Int, to: Int): String? {
        val k = "\"$key\":"; val p = text.indexOf(k, from); if (p < 0 || p > to) return null
        return jsonStringAt(text, p + k.length)
    }

    data class FbRep(val url: String, val audio: Boolean, val width: Int, val height: Int, val bandwidth: Long, val codecs: String)

    fun parseMpd(xml: String): List<FbRep> = Regex("""<Representation\b([^>]*)>(.*?)</Representation>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
        val attrs = m.groupValues[1]; val inner = m.groupValues[2]
        fun at(n: String) = Regex("""\b$n="([^"]*)"""").find(attrs)?.groupValues?.get(1)
        val base = Regex("""<BaseURL>([^<]+)</BaseURL>""").find(inner)?.groupValues?.get(1)?.replace("&amp;", "&")?.trim() ?: return@mapNotNull null
        val mime = at("mimeType") ?: ""
        val codecs = at("codecs") ?: ""
        val isAudio = mime.startsWith("audio") || codecs.startsWith("mp4a") || at("audioSamplingRate") != null || inner.contains("AudioChannelConfiguration")
        FbRep(base, isAudio, at("width")?.toIntOrNull() ?: 0, at("height")?.toIntOrNull() ?: 0, at("bandwidth")?.toLongOrNull() ?: 0, at("codecs") ?: "")
    }.toList()

    private fun tagOf(url: String): String = SocialGrabber.queryParam(url, "efg")?.let(SocialGrabber::b64)
        ?.let { Regex("\"vencode_tag\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: ""

    private fun ogTitle(html: String): String? {
        val raw = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title[^>]*>([^<]*)</title>""").find(html)?.groupValues?.get(1)
        return raw?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#039;", "'")?.replace("&#39;", "'")
            ?.replace(Regex("""&#x([0-9a-fA-F]+);""")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            ?.replace(Regex("""\s*\|\s*Facebook\s*$"""), "")?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotBlank() && it != "Facebook" }
    }

    /**
     * Finds the video on a Facebook page's HTML. [videoId] picks the right one when the page also carries
     * other videos (a reel page preloads the next reels). [probe] says what a file holds ("both"/"vide"/"soun").
     */
    fun parseFacebook(html: String, videoId: String?, probe: (String) -> String? = { null }): GrabResult? {
        val keys = listOf("\"browser_native_sd_url\":", "\"browser_native_hd_url\":", "\"playable_url\":", "\"playable_url_quality_hd\":")
        val hits = keys.flatMap { k -> Regex(Regex.escape(k)).findAll(html).map { it.range.first }.toList() }.sorted()
        if (hits.isEmpty()) return null
        // Each video object: its file fields, then its "id". Group hits by the first id that follows them.
        data class Block(val id: String?, val start: Int, val end: Int)
        val blocks = ArrayList<Block>()
        for (h in hits) {
            val idm = Regex("""\"id\":\"(\d{6,})\"""").find(html, h)
            val idEnd = idm?.range?.first ?: (h + 4000)
            val last = blocks.lastOrNull()
            if (last != null && last.end == idEnd) continue
            blocks += Block(idm?.groupValues?.get(1), h, idEnd)
        }
        val block = blocks.firstOrNull { videoId != null && it.id == videoId } ?: blocks.first()
        val prevEnd = blocks.getOrNull(blocks.indexOf(block) - 1)?.end ?: 0
        val dashKey = html.lastIndexOf("\"dash_manifest\":", block.start).takeIf { it >= prevEnd }
        val from = minOf(block.start, dashKey ?: block.start)
        val sd = valueOf(html, "browser_native_sd_url", from, block.end) ?: valueOf(html, "playable_url", from, block.end)
        val hd = valueOf(html, "browser_native_hd_url", from, block.end) ?: valueOf(html, "playable_url_quality_hd", from, block.end)
        val reps = dashKey?.let { jsonStringAt(html, it + "\"dash_manifest\":".length) }?.let(::parseMpd) ?: emptyList()
        val audio = reps.filter { it.audio }.maxByOrNull { it.bandwidth }
        val videos = reps.filter { !it.audio && it.height > 0 }.let { v -> v.filter { it.codecs.startsWith("avc") }.ifEmpty { v } }
            .sortedByDescending { it.bandwidth }.distinctBy { minOf(it.width, it.height) }
        val opts = ArrayList<GrabOption>()
        fun q(w: Int, h: Int) = "${minOf(w, h)}p"
        // Single files (no joining needed): keep HD only when it really is one file with sound.
        val singles = listOfNotNull(hd?.let { "HD" to it }, sd?.let { "SD" to it }).distinctBy { it.second }
        for ((name, u) in singles) {
            val kind = probe(u) ?: if (tagOf(u).contains("dash", true)) "vide" else "both"
            when {
                kind == "both" -> opts += GrabOption(u, "video", "$name (single file, with sound)")
                kind == "vide" && audio != null -> opts += GrabOption(u, "video", "$name (joined with sound)", audioUrl = audio.url)
                kind == "vide" && audio == null -> opts += GrabOption(u, "video", "$name (no sound)")
            }
        }
        // DASH qualities (video-only files): CDM+ joins the audio in after download.
        if (audio != null) videos.forEach { v -> opts += GrabOption(v.url, "video", "${q(v.width, v.height)} (joined with sound)", v.width, v.height, audioUrl = audio.url) }
        if (opts.isEmpty()) return null
        audio?.let { opts += GrabOption(it.url, "audio", "Audio only", ext = "m4a") }
        val title = ogTitle(html)?.replace(Regex("""^[\d.,]+[KMB]? views · [\d.,]+[KMB]? reactions \| """), "") ?: "Facebook video"
        val thumb = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        return GrabResult("Facebook", title.take(60), null, listOf(GrabItem(0, opts, thumb)),
            headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.facebook.com/"))
    }

    /** What an MP4 holds, read from its header (first 512 KB, enough for the moov of a long video): "both", "vide", "soun" or null. */
    fun probeTracks(client: OkHttpClient, url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("Range", "bytes=0-524287").header("User-Agent", DESKTOP_UA).header("Referer", "https://www.facebook.com/").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.source()?.let { s -> s.request(524288); s.buffer.readByteArray(minOf(524288L, s.buffer.size)) } ?: return null
            val h = io.github.kyotobyte.cdmplus.engine.Mp4Joiner.handlersIn(bytes)
            when { "vide" in h && "soun" in h -> "both"; "vide" in h -> "vide"; "soun" in h -> "soun"; else -> null }
        }
    }.getOrNull()

    fun grabFacebook(client: OkHttpClient, url0: String, probe: ((String) -> String?)? = null): GrabResult {
        val probe = probe ?: { u: String -> probeTracks(client, u) }
        val url = url0.replace(Regex("""//(?:m|mbasic|web|touch|mobile)\.facebook\.com/""", RegexOption.IGNORE_CASE), "//www.facebook.com/")
            .let { if (it.startsWith("http")) it else "https://$it" }
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none", "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-User" to "?1", "Upgrade-Insecure-Requests" to "1",
        )
        val (code, res) = runCatching { get(client, url, headers) }.getOrElse { throw GrabException("Couldn't reach Facebook. Check your connection and try again.", it.message) }
        val (final, html) = res
        if (code !in 200..299) throw GrabException("Facebook answered $code for this link. If it's a private or friends-only post, open it in the CDM+ browser, log in to Facebook, play it, then tap GRAB.")
        val id = fbVideoId(final) ?: fbVideoId(url)
        parseFacebook(html, id, probe)?.let { return it }
        val login = final.contains("/login") || html.contains("id=\"login_form\"") && !html.contains("browser_native")
        throw GrabException(
            if (login) "Facebook wants a login for this video (private, friends-only or a group post). Open it in the CDM+ browser, log in to Facebook there, play it, then tap GRAB."
            else "Couldn't find a video in this Facebook post. If it's a photo post there's nothing to download; if it's private, open it in the CDM+ browser, log in, play it, then tap GRAB.",
            "final=$final")
    }
}
