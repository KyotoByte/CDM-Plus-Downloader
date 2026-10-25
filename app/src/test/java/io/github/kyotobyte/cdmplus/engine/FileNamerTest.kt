package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FileNamerTest {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/vpn-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }

    @Test fun contentDispositionWins() {
        assertEquals("Holiday Clip.mp4", FileNamer.improve("download.bin", "attachment; filename=\"Holiday Clip.mp4\"", "https://x.com/get?id=1", "application/octet-stream"))
        assertEquals("गाना.mp3", FileNamer.improve("download", "attachment; filename*=UTF-8''%E0%A4%97%E0%A4%BE%E0%A4%A8%E0%A4%BE.mp3", null, null))
        // disposition without extension + real mime
        assertEquals("report.pdf", FileNamer.improve("x", "attachment; filename=report", null, "application/pdf"))
    }

    @Test fun urlWithQueryAndRedirects() {
        assertEquals("video.mp4", SegmentedDownloader.fileNameFromUrl("https://cdn.example.com/v/video.mp4?token=abc123&exp=99"))
        // browser guessed .bin from application/octet-stream; final URL has the real extension
        assertEquals("video.mp4", FileNamer.improve("video.bin", null, "https://cdn.example.com/v/video.mp4?token=abc", "application/octet-stream"))
        assertEquals("downloadfile.mp4".let { "clip.mp4" }, FileNamer.improve("downloadfile.bin", null, "https://cdn.example.com/x/clip.mp4?sig=1", "application/octet-stream"))
        assertEquals("song.mp3", FileNamer.improve("song.bin", null, "https://a.b/c/track.mp3?x=1", null))
        // a good name is kept
        assertEquals("movie.mkv", FileNamer.improve("movie.mkv", null, "https://a.b/x.mp4", "video/mp4"))
    }

    @Test fun mimeFallbackAndOctetStreamIsUnknown() {
        assertEquals("stream.mp4", FileNamer.improve("stream", null, "https://a.b/play?id=7", "video/mp4"))
        assertEquals("get.bin", FileNamer.improve("get.bin", null, "https://a.b/get.php?id=7", "application/octet-stream"))
        assertEquals("watch.webm", FileNamer.improve("watch.php", null, "https://a.b/watch.php?v=1", "video/webm; codecs=vp9"))
    }

    @Test fun magicBytes() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()
        assertEquals("mp4", FileNamer.sniff(mp4))
        assertEquals("m4a", FileNamer.sniff(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()))
        assertEquals("mp3", FileNamer.sniff("ID3\u0004".toByteArray()))
        assertEquals("mp3", FileNamer.sniff(b(0xFF, 0xFB, 0x90, 0x44)))
        assertEquals("zip", FileNamer.sniff(b(0x50, 0x4B, 0x03, 0x04)))
        assertEquals("pdf", FileNamer.sniff("%PDF-1.7".toByteArray()))
        assertEquals("png", FileNamer.sniff(b(0x89, 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("jpg", FileNamer.sniff(b(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("mkv", FileNamer.sniff(b(0x1A, 0x45, 0xDF, 0xA3, 0x93)))
        assertNull(FileNamer.sniff(b(1, 2, 3, 4)))
        assertEquals("clip.mp4", FileNamer.afterDownload("clip.bin", mp4))
        assertEquals("clip.mkv", FileNamer.afterDownload("clip.mkv", mp4))
        log("file names: Content-Disposition, URL with ?token=, octet-stream, MIME and magic bytes (mp4/m4a/mp3/zip/pdf/png/jpg/mkv) all resolve correctly")
    }

    @Test fun endToEndOctetStreamMp4IsSavedAsMp4(): Unit = runBlocking {
        // Server sends application/octet-stream, no Content-Disposition, URL ends in .bin
        val data = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray() + ByteArray(600_000) { (it * 31).toByte() }
        TestServer(data, rangeSupport = true).use { srv ->
            val dir = File(System.getProperty("java.io.tmpdir"), "cdmn" + System.nanoTime()).apply { mkdirs() }
            var publishedName = ""
            val platform = object : DownloadPlatform {
                override fun partialDir() = dir
                override suspend fun publish(item: DownloadItem, file: File): Pair<String?, String?> { publishedName = item.fileName; return null to file.path }
            }
            val core = DownloadCore(CoroutineScope(SupervisorJob() + Dispatchers.IO), DownloadStore(File(dir, "db.json")), OkHttpClient(), platform) { EngineSettings(threads = 4) }
            core.add(srv.url)
            core.awaitIdle()
            assertEquals("file.mp4", publishedName)
            assertEquals("file.mp4", core.items.value.first().fileName)
            assertEquals("video/mp4", core.items.value.first().mimeType)
            log("end-to-end: an MP4 served as application/octet-stream from a .bin URL is saved as file.mp4 (video/mp4)")
            dir.deleteRecursively()
        }
    }
}
