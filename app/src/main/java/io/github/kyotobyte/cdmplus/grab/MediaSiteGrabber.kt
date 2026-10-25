package io.github.kyotobyte.cdmplus.grab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * YouTube (videos, Shorts, playlists) plus SoundCloud, Bandcamp, PeerTube and media.ccc.de,
 * through NewPipeExtractor (GPL-3.0, pure Java). Gives every quality: progressive files with
 * sound, and high-quality video-only streams that CDM+ merges with an audio stream on the phone.
 * DRM/paid content is refused.
 */
object MediaSiteGrabber {
    @Volatile private var ready = false
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val SITES = Regex("""(youtube(-nocookie)?\.com/(watch|shorts/|playlist|live/|embed/|v/)|youtu\.be/|music\.youtube\.com/(watch|playlist)|soundcloud\.com/|bandcamp\.com/|media\.ccc\.de/|peertube|framatube\.org)""", RegexOption.IGNORE_CASE)
    fun isSupported(url: String) = SITES.containsMatchIn(url)

    /**
     * Cleans what YouTube's Share button and the browser hand over: picks the link out of shared text
     * ("Title https://youtu.be/ID?si=..."), maps m./music./youtu.be/shorts/live to a plain watch URL,
     * drops tracking params (si, pp, feature) and keeps the playlist id when there is no video id.
     */
    fun normalize(input: String): String {
        val raw = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(input)?.value?.trimEnd('.', ',', ')', ']') ?: input.trim()
        val u = runCatching { java.net.URI(raw) }.getOrNull() ?: return raw
        val host = (u.host ?: return raw).lowercase().removePrefix("www.")
        val query = (u.rawQuery ?: "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val path = u.rawPath ?: ""
        val id = Regex("""^[A-Za-z0-9_-]{11}$""")
        val vid: String? = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> when {
                query["v"] != null -> query["v"]
                Regex("""^/(shorts|live|embed|v|e)/""").containsMatchIn(path) -> path.split('/').getOrNull(2)
                else -> null
            }
            else -> return raw
        }
        if (vid != null && id.matches(vid)) return "https://www.youtube.com/watch?v=$vid"
        val list = query["list"]
        if (list != null && (host.endsWith("youtube.com"))) return "https://www.youtube.com/playlist?list=$list"
        return raw
    }

    fun init(client: OkHttpClient) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val loc = java.util.Locale.getDefault()
            val lang = loc.language.ifBlank { "en" }
            val country = loc.country.takeIf { it.length == 2 } ?: "US"
            NewPipe.init(OkDownloader(client), runCatching { Localization(lang, country) }.getOrElse { Localization("en", "US") },
                runCatching { org.schabi.newpipe.extractor.localization.ContentCountry(country) }.getOrElse { org.schabi.newpipe.extractor.localization.ContentCountry("US") })
            ready = true
        }
    }

    /** Blocking entry used by the self-test against the shipped (minified) app classes. */
    @JvmStatic fun grabBlocking(url: String): String = kotlinx.coroutines.runBlocking {
        val r = grab(url, OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build())
        buildString {
            append("platform=").append(r.platform).append(" title=").append(r.title).append(" author=").append(r.author).append('\n')
            r.items.flatMap { it.options }.forEach { append(" ").append(it.kind).append(" | ").append(it.label).append(" | merge=").append(it.audioUrl != null).append(" | ").append(it.url).append('\n') }
            r.playlist?.let { append(" playlist entries=").append(it.size).append('\n') }
        }
    }

    suspend fun grab(url0: String, client: OkHttpClient): GrabResult = withContext(Dispatchers.IO) {
        val url = normalize(url0)
        try { init(client) } catch (e: Throwable) { throw GrabException("YouTube engine failed to start (${e.javaClass.simpleName}: ${e.message?.take(100)})", details(url0, e)) }
        val service = runCatching { NewPipe.getServiceByUrl(url) }.getOrElse { throw GrabException("This site isn't supported", details(url0, it)) }
        val isPlaylist = runCatching { service.playlistLHFactory?.acceptUrl(url) == true }.getOrDefault(false) &&
            !Regex("[?&]v=").containsMatchIn(url) && !url.contains("/shorts/")
        if (isPlaylist) {
            val pl = try { PlaylistInfo.getInfo(service, url) } catch (e: Throwable) { throw friendly(e, url0) }
            val entries = pl.relatedItems.map { it.name to it.url }
            if (entries.isEmpty()) throw GrabException("This playlist is empty or private")
            return@withContext GrabResult(service.serviceInfo.name, pl.name, pl.uploaderName, playlist = entries)
        }
        val info = try { StreamInfo.getInfo(service, url) } catch (e: Throwable) { throw friendly(e, url0) }
        val opts = ArrayList<GrabOption>()
        val audio = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val bestM4a = audio.filter { it.format == MediaFormat.M4A }.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
        val bestWebmA = audio.filter { it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.OPUS }.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
        // High quality: video-only + matching audio (MP4+M4A or WebM+Opus), merged on the phone.
        info.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .groupBy { it.height to it.format }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.bitrate } }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }.thenBy { if (it.format == MediaFormat.MPEG_4) 0 else 1 })
            .forEach { v ->
                val mp4 = v.format == MediaFormat.MPEG_4
                val a = if (mp4) bestM4a else bestWebmA
                if (a != null && (!mp4 || v.codec?.startsWith("avc") == true)) {
                    opts += GrabOption(v.content, "video", "${v.height}p${if (v.fps > 30) v.fps.toString() else ""} ${if (mp4) "MP4" else "WebM"}${v.codec?.let { " · " + it.substringBefore('.') } ?: ""}", v.width, v.height, ext = if (mp4) "mp4" else "webm", audioUrl = a.content)
                }
            }
        // Progressive (video + sound in one file, usually 360p): always works, no merge needed.
        info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }.sortedByDescending { it.height }.forEach { v ->
            opts += GrabOption(v.content, "video", "${v.resolution} ${v.format?.suffix?.uppercase() ?: ""} (with sound, no merge)".replace("  ", " "), v.width, v.height, ext = v.format?.suffix ?: "mp4")
        }
        audio.sortedWith(compareBy<org.schabi.newpipe.extractor.stream.AudioStream> { if (it.format == MediaFormat.M4A) 0 else 1 }.thenByDescending { maxOf(it.averageBitrate, it.bitrate) })
            .distinctBy { it.format to maxOf(it.averageBitrate, it.bitrate) / 16 }
            .forEach { a -> opts += GrabOption(a.content, "audio", "Audio only ${if (a.format == MediaFormat.M4A) "M4A" else "Opus/WebM"} · ${maxOf(a.averageBitrate, a.bitrate).let { if (it > 10_000) it / 1000 else it }} kbps", ext = if (a.format == MediaFormat.M4A) "m4a" else (a.format?.suffix ?: "webm")) }
        if (opts.isEmpty()) {
            if (info.hlsUrl?.isNotBlank() == true) opts += GrabOption(info.hlsUrl, "video", "Live/HLS stream (saved as MP4)", ext = "mp4")
            else throw GrabException("No downloadable streams for this video")
        }
        val subs = info.subtitles.filter { it.isUrl }.map { s -> GrabOption(s.content, "subtitle", "Subtitles: ${s.displayLanguageName}${if (s.isAutoGenerated) " (auto)" else ""}", ext = s.extension ?: "vtt") }
        GrabResult(service.serviceInfo.name, info.name, info.uploaderName, listOf(GrabItem(0, opts, info.thumbnails.maxByOrNull { it.height }?.url)),
            musicTitle = "${info.name}${info.uploaderName?.let { " - $it" } ?: ""}", headers = mapOf("User-Agent" to UA), subtitles = subs)
    }

    private fun friendly(e: Throwable, url: String): Exception = when (e) {
        is PaidContentException -> GrabException("Protected content, can't be downloaded (paid / DRM)", details(url, e))
        is PrivateContentException -> GrabException("This video is private", details(url, e))
        is AgeRestrictedContentException -> GrabException("Age-restricted video: YouTube asks for a login for this one", details(url, e))
        is ContentNotAvailableException -> GrabException("Not available: ${e.message?.take(120) ?: "removed or blocked in this country"}", details(url, e))
        is java.io.IOException -> GrabException("Network problem reaching the site (${e.javaClass.simpleName}: ${e.message?.take(100)}). Check the connection, or turn off proxy/Tor and try again.", details(url, e))
        else -> GrabException("Couldn't read this video (${e.javaClass.simpleName}: ${e.message?.take(120)}). The site may have changed; a CDM+ update will fix it.", details(url, e))
    }

    fun details(url: String, e: Throwable): String = buildString {
        append("CDM+ grab error\nLink: ").append(url).append("\nNormalized: ").append(runCatching { normalize(url) }.getOrDefault("?"))
        append("\nAndroid: ").append(runCatching { "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }.getOrDefault("n/a"))
        append("\nLocale: ").append(java.util.Locale.getDefault()).append("\n\n")
        append(e.stackTraceToString().take(6000))
    }

    private class OkDownloader(private val http: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            request.headers().forEach { (name, values) -> values.forEach { b.addHeader(name, it) } }
            if (request.headers().keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", UA)
            return http.newCall(b.build()).execute().use { r ->
                Response(r.code, r.message, r.headers.names().associateWith(r.headers::values), r.body?.string().orEmpty(), r.request.url.toString())
            }
        }
    }
}
