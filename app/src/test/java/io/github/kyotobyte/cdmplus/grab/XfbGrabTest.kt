package io.github.kyotobyte.cdmplus.grab

import io.github.kyotobyte.cdmplus.engine.Mp4Joiner
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Patch 1: X and Facebook videos, through the app's own GRAB entry, on real public posts. */
class XfbGrabTest {
    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private val dir = File("build/xfb").apply { mkdirs() }
    private val report = File(dir, "report.txt")
    private fun log(s: String) { report.appendText(s + "\n"); println(s) }

    @Test fun routing() {
        val x = listOf("https://x.com/NASA/status/2042840027109957758", "https://twitter.com/NASA/status/2042840027109957758?s=20",
            "https://mobile.twitter.com/NASA/status/2042840027109957758", "https://x.com/i/status/2042840027109957758", "https://t.co/AbC123xyz")
        x.forEach { assertEquals(it, "X", SocialGrabber.platformOf(it)) }
        val fb = listOf("https://www.facebook.com/share/r/1FAwVtbr7g/", "https://www.facebook.com/share/v/1AbCdEf/", "https://www.facebook.com/reel/897061959790159/",
            "https://m.facebook.com/watch/?v=1450137903652075", "https://fb.watch/abcDEF123/", "https://www.facebook.com/NASA/videos/1450137903652075/",
            "https://web.facebook.com/NASA/posts/pfbid0abc")
        fb.forEach { assertEquals(it, "Facebook", SocialGrabber.platformOf(it)) }
        assertNull(SocialGrabber.platformOf("https://www.facebook.com/NASA"))
        assertNull(SocialGrabber.platformOf("https://x.com/NASA"))
        // untouched
        assertEquals("Instagram", SocialGrabber.platformOf("https://www.instagram.com/reel/DSu-FEdgnW5/"))
        assertEquals("TikTok", SocialGrabber.platformOf("https://www.tiktok.com/@bbcnews/video/7623948383312465174"))
        assertEquals("897061959790159", XfbGrabber.fbVideoId("https://www.facebook.com/reel/897061959790159/?rdid=x"))
        assertEquals("1450137903652075", XfbGrabber.fbVideoId("https://www.facebook.com/NASA/videos/some-title/1450137903652075/"))
        assertTrue(XfbGrabber.xToken("2042840027109957758").isNotBlank())
    }

    @Test fun mpdParsing() {
        val xml = """<MPD><Period><AdaptationSet><Representation id="1" mimeType="video/mp4" codecs="avc1.64001F" width="1280" height="720" bandwidth="900000"><BaseURL>https://v.fbcdn.net/a.mp4?x=1&amp;y=2</BaseURL></Representation>
            <Representation id="2" mimeType="video/mp4" codecs="avc1.4D401E" width="640" height="360" bandwidth="300000"><BaseURL>https://v.fbcdn.net/b.mp4</BaseURL></Representation></AdaptationSet>
            <AdaptationSet><Representation id="3" mimeType="audio/mp4" codecs="mp4a.40.5" bandwidth="64000"><BaseURL>https://v.fbcdn.net/c.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        val reps = XfbGrabber.parseMpd(xml)
        assertEquals(3, reps.size); assertEquals("https://v.fbcdn.net/a.mp4?x=1&y=2", reps[0].url); assertTrue(reps[2].audio)
    }

    private fun download(url: String, headers: Map<String, String>, out: File): Long {
        val b = Request.Builder().url(url); headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r -> assertTrue("HTTP ${r.code} for $url", r.isSuccessful); out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) } }
        return out.length()
    }

    private fun ffprobe(f: File): String = runCatching {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height:format=duration", "-of", "compact", f.path).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().replace("\n", " ; ").also { p.waitFor() }
    }.getOrDefault("ffprobe n/a")

    @Test fun liveXAndFacebook() = runBlocking {
        report.writeText("")
        val cases = listOf(
            "x_nasa" to "https://x.com/NASA/status/2042840027109957758",
            "x_twitter_link" to "https://twitter.com/NASA/status/2042840027109957758?s=46",
            "fb_his_share_reel" to "https://www.facebook.com/share/r/1FAwVtbr7g/",
            "fb_nasa_video" to "https://www.facebook.com/NASA/videos/1450137903652075/",
            "fb_m_reel" to "https://m.facebook.com/reel/897061959790159/",
        )
        var ok = 0
        for ((name, u) in cases) {
            val r = runCatching { SocialGrabber(client).grab(u) }
            if (r.isFailure) { log("$name FAIL ${r.exceptionOrNull()?.message}"); continue }
            val g = r.getOrThrow()
            log("$name OK platform=${g.platform} title=\"${g.title}\" options=" + g.items.flatMap { it.options }.joinToString(" | ") { it.label + (if (it.audioUrl != null) " [+audio]" else "") })
            val best = g.items.first().options.first { it.kind == "video" }
            val v = File(dir, "$name.mp4"); download(best.url, g.headers, v)
            val vh = Mp4Joiner.handlersIn(v.readBytes().copyOf(minOf(v.length().toInt(), 2_000_000)))
            var line = "   best=${best.label} ${v.length()} B probe=${ffprobe(v)}"
            if (best.audioUrl != null) {
                val a = File(dir, "$name.audio.m4a"); download(best.audioUrl!!, g.headers, a)
                line += " + audio ${a.length()} B probe=${ffprobe(a)}"
                assertTrue("$name video track", "vide" in vh)
            } else assertTrue("$name video+sound in one file: $vh", "vide" in vh && "soun" in vh)
            log(line); ok++
        }
        assertTrue("at least 4 of ${cases.size} live posts grabbed", ok >= 4)
    }
}
