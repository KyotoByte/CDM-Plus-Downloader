package io.github.kyotobyte.cdmplus.grab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Build 6: every grabber end-to-end on live links, through the app's own entry (same routing as MainActivity). */
class LiveGrabTest {
    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private val dir = File("build/live").apply { mkdirs() }
    private val report = File(dir, "report.txt")

    private suspend fun appGrab(u: String): GrabResult =
        if (MediaSiteGrabber.isSupported(u)) MediaSiteGrabber.grab(u, client) else SocialGrabber(client).grab(u)

    private fun firstMb(url: String, headers: Map<String, String>, out: File): Int {
        val b = Request.Builder().url(url).header("Range", "bytes=0-1048575")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r -> out.writeBytes(r.body?.bytes() ?: ByteArray(0)); return r.code }
    }

    @Test fun allGrabbersLive() = runBlocking {
        val cases = listOf(
            "yt_video" to "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "yt_shorts" to "https://youtube.com/shorts/tPEE9ZwTmy0?si=AbCdEf123",
            "yt_share" to "Never Gonna Give You Up\nhttps://youtu.be/dQw4w9WgXcQ?si=Xy12Ab34Cd56Ef78",
            "ig_reel_his" to "https://www.instagram.com/reel/DedtUFABVPq/?l=1",
            "ig_reel_b4" to "https://www.instagram.com/reel/DSu-FEdgnW5/",
            "ig_post" to "https://www.instagram.com/p/DTK6R8zAaRM/",
            "tt_video" to "https://www.tiktok.com/@bbcnews/video/7623948383312465174",
            "tt_photo" to "https://www.tiktok.com/@theeepika/photo/7694849164471012622",
        )
        report.writeText("")
        for ((name, raw) in cases) {
            val u = if (raw.contains('\n')) Regex("https?://\\S+").find(raw)!!.value else raw
            val line = try {
                val r = appGrab(u)
                val opts = r.items.flatMap { it.options }
                val v = opts.firstOrNull { it.kind == "video" && it.audioUrl == null } ?: opts.firstOrNull { it.kind == "video" }
                val pick = v ?: opts.firstOrNull()
                var dl = "no option"
                if (pick != null) {
                    val f = File(dir, "$name.${if (pick.kind == "image") "jpg" else pick.ext}")
                    val code = firstMb(pick.url, r.headers, f)
                    dl = "HTTP $code ${f.length()} bytes -> ${f.name}"
                    pick.audioUrl?.let { a -> val fa = File(dir, "$name.audio.m4a"); val c2 = firstMb(a, r.headers, fa); dl += "; audio HTTP $c2 ${fa.length()} bytes -> ${fa.name}" }
                }
                "$name\tOK\t${r.platform}: '${r.title.take(40)}' items=${r.items.size} video=${opts.count { it.kind == "video" }} image=${opts.count { it.kind == "image" }} first=${pick?.label}\t$dl"
            } catch (e: Throwable) {
                "$name\tFAIL\t${e.javaClass.simpleName}: ${e.message?.take(200)}"
            }
            println(line); report.appendText(line + "\n")
        }
    }
}
