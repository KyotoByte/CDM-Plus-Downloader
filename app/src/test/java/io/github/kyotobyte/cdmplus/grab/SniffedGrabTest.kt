package io.github.kyotobyte.cdmplus.grab

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SniffedGrabTest {
    private fun efg(json: String): String {
        val b = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        return java.net.URLEncoder.encode(b, "UTF-8")
    }
    private val vHi = "https://scontent-bom1-1.cdninstagram.com/o1/v/t16/f2/m86/AQabc_video_hi.mp4?efg=${efg("{\"vencode_tag\":\"xpv_dash_basline_1_v1\",\"bitrate\":1800000}")}&_nc_ht=x&bytestart=0&byteend=1023"
    private val vHi2 = vHi.replace("bytestart=0&byteend=1023", "bytestart=1024&byteend=99999")
    private val vLo = "https://scontent-bom1-1.cdninstagram.com/o1/v/t16/f2/m86/AQabc_video_lo.mp4?efg=${efg("{\"vencode_tag\":\"xpv_dash_basline_3_v1\",\"bitrate\":400000}")}&bytestart=0&byteend=500"
    private val aud = "https://scontent-bom1-1.cdninstagram.com/o1/v/t16/f2/m69/AQabc_audio.mp4?efg=${efg("{\"vencode_tag\":\"dash_ln_heaac_vbr3_audio\",\"bitrate\":64000}")}&bytestart=0&byteend=800"

    @Test fun reelFromSniffedDash() {
        val r = SocialGrabber.fromSniffed("Instagram", listOf("https://static.cdninstagram.com/rsrc.php/x.js", vLo, aud, vHi, vHi2))!!
        val o = r.items.single().options
        assertEquals("video", o[0].kind)
        assertTrue(o[0].url.contains("video_hi.mp4")); assertFalse(o[0].url.contains("bytestart")); assertFalse(o[0].url.contains("byteend"))
        assertTrue(o[0].audioUrl!!.contains("audio.mp4")); assertFalse(o[0].audioUrl!!.contains("bytestart"))
        assertTrue(o[1].url.contains("video_lo.mp4"))
        assertEquals("audio", o.last().kind)
        assertEquals(3, o.size) // hi, lo, audio-only (repeats deduped)
    }

    @Test fun progressiveWinsAndNoSoundIsLabelled() {
        val prog = "https://scontent.cdninstagram.com/v/t50/abc_prog.mp4?efg=${efg("{\"vencode_tag\":\"xpv_progressive.INSTAGRAM.CLIPS.C3.720.dash_baseline_1_v1\"}")}"
        val r = SocialGrabber.fromSniffed("Instagram", listOf(vHi, prog))!!
        assertTrue(r.items[0].options[0].label.contains("with sound")); assertTrue(r.items[0].options[0].url.contains("prog"))
        val onlyVideo = SocialGrabber.fromSniffed("Instagram", listOf(vHi))!!
        assertTrue(onlyVideo.items[0].options[0].label.contains("no sound"))
        assertNull(SocialGrabber.fromSniffed("Instagram", listOf("https://example.com/a.mp4")))
    }

    @Test fun reelNeverAnswersWithOnlyACover() = runBlocking {
        val page = """<html><head><meta property="og:image" content="https://scontent.cdninstagram.com/cover.jpg"><meta property="og:title" content="The Age of Adaline"></head></html>"""
        val reel = "https://www.instagram.com/reel/DedtUFABVPq/?l=1"
        // Live page has only a cover, but the browser caught the video: the video is offered.
        val r = SocialGrabber.fromPage(reel, page, listOf(vHi, aud)) { throw GrabException("blocked") }
        assertEquals("video", r.items[0].options[0].kind); assertEquals("The Age of Adaline", r.title)
        // Nothing caught and the fetch fails: a clear error instead of a lone cover photo.
        val e = runCatching { SocialGrabber.fromPage(reel, page, emptyList()) { throw GrabException("blocked") } }.exceptionOrNull()
        assertTrue(e is GrabException && e.message!!.contains("Couldn't find the video"))
        // A photo post may answer with photos.
        val p = SocialGrabber.fromPage("https://www.instagram.com/p/ABC/", page, emptyList()) { throw GrabException("blocked") }
        assertEquals("image", p.items[0].options[0].kind)
        assertTrue(SocialGrabber.isVideoPost(reel)); assertFalse(SocialGrabber.isVideoPost("https://www.instagram.com/p/ABC/"))
    }

    @Test fun base64AndRangeHelpers() {
        assertEquals("{\"a\":1}", SocialGrabber.b64(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"a\":1}".toByteArray())))
        assertEquals("https://x/a.mp4?k=1", SocialGrabber.stripRange("https://x/a.mp4?bytestart=0&k=1&byteend=9"))
    }
}
