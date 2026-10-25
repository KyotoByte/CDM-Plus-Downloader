package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Enough : Exception()

class TorrentTest {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/vpn-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }

    /** Real swarm: the official Debian netinst torrent. Downloads a chunk, pauses, resumes. */
    @Test fun realTorrentDownloadPauseResume(): Unit = runBlocking {
        val index = OkHttpClient().newCall(okhttp3.Request.Builder().url("https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/").build()).execute().use { it.body!!.string() }
        val name = Regex("href=\"(debian-[0-9.]+-amd64-netinst\\.iso\\.torrent)\"").find(index)!!.groupValues[1]
        val url = "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/$name"
        val work = File(System.getProperty("java.io.tmpdir"), "cdmt" + System.nanoTime())
        val td = TorrentDownloader(work, SpeedLimiter())
        val item = DownloadItem("t1", url, name)
        var last: DownloadItem? = null
        val t0 = System.nanoTime()
        try {
            withTimeoutOrNull(150_000) {
                td.download(item, OkHttpClient()) { snap ->
                    last = snap
                    if (snap.downloadedBytes >= 12L * 1024 * 1024) throw Enough()
                }
            }
        } catch (_: Enough) {}
        val s1 = (System.nanoTime() - t0) / 1e9
        val got = last!!
        log("torrent: $name -> '${got.fileName}' ${got.totalBytes / 1048576} MB, ${got.torrentPeers} peers, ${got.downloadedBytes / 1048576} MB hash-verified in %.0f s, then paused".format(s1))
        assertTrue("no data from the swarm", got.downloadedBytes > 2L * 1024 * 1024)
        // Resume: same item id + folder; libtorrent re-checks the existing pieces first
        var firstSeen = -1L
        try {
            withTimeoutOrNull(60_000) {
                td.download(item, OkHttpClient()) { snap ->
                    firstSeen = maxOf(firstSeen, snap.downloadedBytes)
                    if (firstSeen >= got.downloadedBytes * 8 / 10) throw Enough()
                }
            }
        } catch (_: Enough) {}
        log("torrent resume: after restart it already had ${firstSeen / 1048576} MB verified (kept, not downloaded again)")
        assertTrue(firstSeen >= got.downloadedBytes * 8 / 10)
        td.stop()
        work.deleteRecursively()
    }
}
