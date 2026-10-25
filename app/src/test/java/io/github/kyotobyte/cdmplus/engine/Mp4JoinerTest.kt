package io.github.kyotobyte.cdmplus.engine

import io.github.kyotobyte.cdmplus.grab.SocialGrabber
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Joins REAL downloaded DASH parts (fetched before the test into /tmp by the build script):
 *  - Instagram reel DSu-FEdgnW5: VP9 video (fragmented MP4) + HE-AAC audio (plain MP4)
 *  - YouTube jNQXAC9IVRw: H.264 video (fragmented MP4) + AAC audio (fragmented MP4)
 * Output is checked again with ffprobe outside the test.
 */
class Mp4JoinerTest {
    private fun join(dir: String, out: String) {
        val v = File("$dir/v.mp4"); val a = File("$dir/a.m4a")
        assumeTrue("real parts not fetched", v.exists() && a.exists())
        val o = File(out); o.delete()
        Mp4Joiner.join(v, a, o)
        val (vc, ac) = Mp4Joiner.counts(o)
        assertEquals(Mp4Joiner.readTrack(v, "vide").samples.size, vc)
        assertEquals(Mp4Joiner.readTrack(a, "soun").samples.size, ac)
        assertTrue(ac > 0)
    }

    @Test fun instagramVp9PlusAac() = join("/tmp/igdash", "/tmp/joined_ig.mp4")
    @Test fun youtubeH264PlusAac() = join("/tmp/ytdash", "/tmp/joined_yt.mp4")

    @Test fun probesRealFiles() {
        val v = File("/tmp/igdash/v.mp4"); val a = File("/tmp/igdash/a.m4a")
        assumeTrue(v.exists() && a.exists())
        assertTrue("vide" in Mp4Joiner.handlersIn(v.readBytes().copyOf(32768)) && "soun" !in Mp4Joiner.handlersIn(v.readBytes().copyOf(32768)))
        assertTrue("soun" in Mp4Joiner.handlersIn(a.readBytes().copyOf(32768)) && "vide" !in Mp4Joiner.handlersIn(a.readBytes().copyOf(32768)))
    }

    @Test fun refusesAudioOnlyAsVideo() {
        val a = File("/tmp/igdash/a.m4a"); assumeTrue(a.exists())
        val o = File("/tmp/joined_bad.mp4")
        assertThrows(java.io.IOException::class.java) { Mp4Joiner.join(a, a, o) }
        assertFalse(o.exists())
    }

    @Test fun truncatedAudioFailsLoudly() {
        val v = File("/tmp/igdash/v.mp4"); val a = File("/tmp/igdash/a.m4a"); assumeTrue(v.exists() && a.exists())
        val cut = File("/tmp/igdash/a_cut.m4a"); cut.writeBytes(a.readBytes().copyOf(a.length().toInt() / 2))
        val o = File("/tmp/joined_cut.mp4")
        assertThrows(java.io.IOException::class.java) { Mp4Joiner.join(v, cut, o) }
        assertFalse(o.exists()); cut.delete()
    }

    /** Real signed Instagram URLs (from /tmp/igurls.txt) with a player's byte range added: stripped, paired, probed live. */
    @Test fun realSniffedUrlsPairVideoWithAudio() {
        val f = File("/tmp/igurls.txt"); assumeTrue(f.exists())
        val (vu, au) = f.readLines().filter { it.isNotBlank() }
        val sniffed = listOf("$vu&bytestart=0&byteend=900", "$au&bytestart=0&byteend=800", "$vu&bytestart=901&byteend=50000")
        val quick = SocialGrabber.fromSniffed("Instagram", sniffed)!!
        val best = quick.items[0].options[0]
        assertFalse(best.url.contains("bytestart")); assertFalse(best.audioUrl!!.contains("byteend"))
        assertTrue(SocialGrabber.hasSound(quick))
        // Live content probe on the real signed URLs.
        assertEquals("vide", SocialGrabber.probeKind(vu)); assertEquals("soun", SocialGrabber.probeKind(au))
        // URLs with no tags at all: only the probe result (kinds) can tell audio from video.
        val plainV = "https://scontent.cdninstagram.com/o1/v/x/AQv.mp4?oh=1&bytestart=0&byteend=9"
        val plainA = "https://scontent.cdninstagram.com/o1/v/x/AQa.mp4?oh=2&bytestart=0&byteend=9"
        val k = mapOf(SocialGrabber.igMediaKey(plainV)!! to "vide", SocialGrabber.igMediaKey(plainA)!! to "soun")
        val r = SocialGrabber.fromSniffed("Instagram", listOf(plainA, plainV), k)!!
        assertEquals("https://scontent.cdninstagram.com/o1/v/x/AQv.mp4?oh=1", r.items[0].options[0].url)
        assertEquals("https://scontent.cdninstagram.com/o1/v/x/AQa.mp4?oh=2", r.items[0].options[0].audioUrl)
        val probed = kotlinx.coroutines.runBlocking { SocialGrabber.fromSniffedProbed("Instagram", sniffed) }!!
        assertTrue(SocialGrabber.hasSound(probed))
    }

    @Test fun otherReelOnPageIsNotMixedIn() {
        fun efg(j: String) = java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(j.toByteArray()), "UTF-8")
        val v1 = "https://s.cdninstagram.com/a/v1.mp4?efg=${efg("{\"vencode_tag\":\"dash_vp9\",\"xpv_asset_id\":111,\"bitrate\":500}")}"
        val a2 = "https://s.cdninstagram.com/a/a2.mp4?efg=${efg("{\"vencode_tag\":\"dash_ln_heaac_vbr3_audio\",\"xpv_asset_id\":222,\"bitrate\":90}")}"
        val a1 = "https://s.cdninstagram.com/a/a1.mp4?efg=${efg("{\"vencode_tag\":\"dash_ln_heaac_vbr3_audio\",\"xpv_asset_id\":111,\"bitrate\":60}")}"
        val v2 = "https://s.cdninstagram.com/a/v2.mp4?efg=${efg("{\"vencode_tag\":\"dash_vp9\",\"xpv_asset_id\":222,\"bitrate\":900}")}"
        val r = SocialGrabber.fromSniffed("Instagram", listOf(v1, a2, a1, v2))!!
        assertTrue(r.items[0].options[0].url.contains("v1.mp4")); assertTrue(r.items[0].options[0].audioUrl!!.contains("a1.mp4"))
    }
}
