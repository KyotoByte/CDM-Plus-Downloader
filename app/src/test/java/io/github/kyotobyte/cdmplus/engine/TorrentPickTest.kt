package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorrentPickTest {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/build4-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }

    /** A multi-file archive.org torrent (has web seeds): pick ONE file, check only that file is downloaded. */
    @Test fun pickOneFileInsideTorrent(): Unit = runBlocking {
        val url = "https://archive.org/download/BigBuckBunny_124/BigBuckBunny_124_archive.torrent"
        val work = File(System.getProperty("java.io.tmpdir"), "cdmp" + System.nanoTime())
        val td = TorrentDownloader(work, SpeedLimiter())
        val item = DownloadItem("p1", url, "bbb")
        val files = td.files(item, OkHttpClient())
        val pick = files.filter { it.size in 20_000..6_000_000 && !it.path.endsWith(".xml") && !it.path.endsWith(".sqlite") }.minByOrNull { it.size } ?: files.minBy { it.size }
        log("Torrent file list: ${files.size} files in '${url.substringAfterLast('/')}' -> picked only '${pick.path.substringAfterLast('/')}' (${pick.size / 1024} KB)")
        val t0 = System.nanoTime()
        val (done, root) = withTimeout(150_000) { td.download(item.copy(torrentFiles = listOf(pick.index)), OkHttpClient()) {} }
        val got = root.walkTopDown().filter { it.isFile }.toList()
        log("Torrent file pick: finished in %.0f s; files on disk: ${got.map { it.name + " " + it.length() / 1024 + " KB" }}; wanted size ${done.totalBytes / 1024} KB".format((System.nanoTime() - t0) / 1e9))
        assertEquals(1, got.size)
        assertEquals(pick.size, got[0].length())
        td.stop(); work.deleteRecursively()
    }
}
