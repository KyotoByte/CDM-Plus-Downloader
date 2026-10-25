package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FeaturesTest {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/vpn-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }

    @Test fun ftpSegmentedDownloadMatchesHttpsCopy(): Unit = runBlocking {
        val ftpUrl = "ftp://ftp.gnu.org/gnu/hello/hello-2.12.tar.gz"
        val httpsUrl = "https://ftp.gnu.org/gnu/hello/hello-2.12.tar.gz"
        val ref = OkHttpClient().newCall(Request.Builder().url(httpsUrl).build()).execute().use { it.body!!.bytes() }
        val refSha = Checksums.of(ref.inputStream()).sha256
        val dl = FtpDownloader(SpeedLimiter())
        val t = FtpDownloader.parse(ftpUrl)
        val size = dl.size(t)
        assertEquals(ref.size.toLong(), size)
        val dest = File.createTempFile("ftp", ".part")
        val item = DownloadItem("f1", ftpUrl, "hello-2.12.tar.gz", threads = 4)
        // force 4 parts even for a small file: plan them by hand
        val len = size!!
        val q = len / 4
        val planned = item.copy(totalBytes = len, segments = (0 until 4).map { Segment(it, it * q, if (it == 3) len - 1 else (it + 1) * q - 1) })
        val t0 = System.nanoTime()
        val done = dl.download(planned, dest) {}
        val ms = (System.nanoTime() - t0) / 1_000_000
        val sha = Checksums.of(dest.inputStream()).sha256
        assertEquals(Status.DONE, done.status)
        assertEquals(refSha, sha)
        log("FTP: $ftpUrl ${len} bytes in 4 parts (REST offsets) in $ms ms, SHA-256 matches the HTTPS copy ($sha)")

        // resume: pretend part 2 and 3 were half done, wipe their tails, resume
        val half = planned.segments.mapIndexed { i, s -> if (i >= 2) s.copy(done = s.length / 2) else s.copy(done = s.length) }
        val bytes = dest.readBytes()
        for (s in half.drop(2)) for (p in (s.start + s.done)..s.end) bytes[p.toInt()] = 0
        dest.writeBytes(bytes)
        val resumed = dl.download(planned.copy(segments = half), dest) {}
        assertEquals(refSha, Checksums.of(dest.inputStream()).sha256)
        log("FTP resume: continued 2 half-finished parts with REST, SHA-256 matches again (${resumed.downloadedBytes} bytes)")
        dest.delete()
    }

    @Test fun checksumAndZip() {
        val data = "CDM+ by KyotoByte".toByteArray()
        val r = Checksums.of(data.inputStream())
        assertEquals("SHA-256", r.matches(r.sha256.uppercase()))
        assertEquals("MD5", r.matches(r.md5))
        assertNull(r.matches("deadbeef"))
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("folder/a.txt")); z.write(data); z.closeEntry()
            z.putNextEntry(ZipEntry("../../evil.txt")); z.write(data); z.closeEntry()
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "zt" + System.nanoTime())
        val n = ZipExtract.extract(bos.toByteArray().inputStream(), dir)
        assertEquals(1, n)
        assertEquals("CDM+ by KyotoByte", File(dir, "folder/a.txt").readText())
        assertFalse(File(dir.parentFile.parentFile, "evil.txt").exists())
        log("checksum: MD5/SHA-1/SHA-256 + compare OK; zip extract OK, path-escape entry refused")
        dir.deleteRecursively()
    }
}
