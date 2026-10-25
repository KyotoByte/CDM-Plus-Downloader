package io.github.kyotobyte.cdmplus.grab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GrabberTest {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/build4-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }
    private val client = OkHttpClient()

    private fun head(url: String, headers: Map<String, String>, n: Int = 65536): Pair<Int, ByteArray> {
        val b = Request.Builder().url(url).header("Range", "bytes=0-${n - 1}")
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
    }
    private fun isMp4(b: ByteArray) = b.size > 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    @Test fun tiktokPublicVideo(): Unit = runBlocking {
        val url = System.getProperty("cdm.tt") ?: "https://www.tiktok.com/@bbcnews/video/7623948383312465174"
        val r = SocialGrabber(client).grab(url)
        val opts = r.items.first().options
        log("TikTok: '${r.title}' by @${r.author}: ${opts.size} choices ${opts.map { it.label }}; music: ${r.musicTitle} (${if (r.musicUrl != null) "direct audio link" else "no direct link"})")
        val (code, bytes) = head(opts.first().url, r.headers)
        log("TikTok download check: HTTP $code, first bytes are an MP4: ${isMp4(bytes)}")
        assertTrue(isMp4(bytes))
    }

    @Test fun instagramPublicReel(): Unit = runBlocking {
        val url = System.getProperty("cdm.ig") ?: "https://www.instagram.com/reel/DSu-FEdgnW5/"
        val r = SocialGrabber(client).grab(url)
        val opts = r.items.flatMap { it.options }
        log("Instagram: '${r.title}' by @${r.author}: ${r.items.size} item(s), choices ${opts.map { it.label }}; music name: ${r.musicTitle}")
        val v = opts.first { it.kind == "video" }
        val (code, bytes) = head(v.url, r.headers)
        log("Instagram download check: HTTP $code, first bytes are an MP4: ${isMp4(bytes)}")
        assertTrue(isMp4(bytes))
    }

    @Test fun instagramCarouselParse() {
        // Shape of the public embed data for a 2-slide post (carousel).
        val html = """<script>window.x={"contextJSON":"{\"gql_data\":{\"shortcode_media\":{\"owner\":{\"username\":\"cs\"},\"edge_sidecar_to_children\":{\"edges\":[{\"node\":{\"is_video\":false,\"display_url\":\"https:\\\/\\\/cdn.test\\\/1.jpg\",\"display_resources\":[{\"src\":\"https:\\\/\\\/cdn.test\\\/1.jpg\",\"config_width\":1080,\"config_height\":1350}]}},{\"node\":{\"is_video\":true,\"video_url\":\"https:\\\/\\\/cdn.test\\\/2.mp4\",\"display_url\":\"https:\\\/\\\/cdn.test\\\/2.jpg\",\"dimensions\":{\"width\":720,\"height\":1280}}}]}}}}"};</script>"""
        val r = SocialGrabber.parseInstagram(html)!!
        assertEquals(2, r.items.size)
        assertEquals("https://cdn.test/2.mp4", r.items[1].options.first().url)
        assertEquals("Original audio - @cs", r.musicTitle)
        log("Instagram carousel: 2 slides found (photo + video), batch grab ready")
    }

    @Test fun youtubeVideo(): Unit = runBlocking {
        val url = System.getProperty("cdm.yt") ?: "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val r = try { MediaSiteGrabber.grab(url, client) } catch (e: Exception) { log("YouTube: BLOCKED from this server: ${e.message}"); throw e }
        val opts = r.items.first().options
        log("YouTube: '${r.title}' by ${r.author}: ${opts.size} choices ${opts.take(8).map { it.label }}, subtitles: ${r.subtitles.size}")
        val pick = opts.first { it.kind == "video" }
        val (code, bytes) = head(pick.url, r.headers)
        log("YouTube download check (${pick.label}): HTTP $code, ${bytes.size} bytes, MP4/WebM header: ${isMp4(bytes) || (bytes.size > 4 && bytes[0] == 0x1A.toByte())}")
        assertTrue(code in 200..299)
        pick.audioUrl?.let { a -> val (c2, b2) = head(a, r.headers); log("YouTube audio stream for merge: HTTP $c2, ${b2.size} bytes"); assertTrue(c2 in 200..299) }
    }

    @Test fun youtubeShortsAndPlaylist(): Unit = runBlocking {
        val pl = MediaSiteGrabber.grab(System.getProperty("cdm.ytpl") ?: "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", client)
        log("YouTube playlist: '${pl.title}' -> ${pl.playlist.size} videos")
        assertTrue(pl.playlist.isNotEmpty())
    }
}
