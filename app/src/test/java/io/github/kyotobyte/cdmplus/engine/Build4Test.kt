package io.github.kyotobyte.cdmplus.engine

import io.github.kyotobyte.cdmplus.data.PasswordVault
import io.github.kyotobyte.cdmplus.ui.Reader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import javax.crypto.KeyGenerator

class Build4Test {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/build4-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }

    class StaticServer(val root: File) {
        private val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val pool = java.util.concurrent.Executors.newCachedThreadPool()
        init { pool.execute { while (!ss.isClosed) { val s = try { ss.accept() } catch (_: Exception) { break }; pool.execute { handle(s) } } } }
        private fun handle(s: java.net.Socket) = s.use { sock ->
            runCatching {
                val r = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = r.readLine() ?: return@runCatching
                while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                val path = line.split(" ")[1].substringBefore('?').removePrefix("/")
                val f = File(root, path)
                val o = sock.getOutputStream()
                if (!f.isFile) { o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@runCatching }
                val type = when (f.extension) { "html" -> "text/html"; "m3u8" -> "application/vnd.apple.mpegurl"; else -> "application/octet-stream" }
                val b = f.readBytes()
                o.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(b); o.flush()
            }
        }
        fun stop(@Suppress("UNUSED_PARAMETER") x: Int) { ss.close(); pool.shutdownNow() }
    }

    private fun serve(root: File): Pair<StaticServer, String> { val s = StaticServer(root); return s to "http://127.0.0.1:${s.port}" }
    private val StaticServer.port: Int get() = javaClass.getDeclaredField("ss").let { it.isAccessible = true; (it.get(this) as java.net.ServerSocket).localPort }

    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText(); p.waitFor(); return o
    }

    private fun makeHls(dir: File, encrypted: Boolean): File {
        dir.mkdirs()
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        File(dir, "k.key").writeBytes(key)
        File(dir, "keyinfo").writeText("k.key\n${dir}/k.key\n00112233445566778899aabbccddeeff\n")
        val args = mutableListOf("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "12", "-c:v", "libx264", "-bf", "2", "-g", "50", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0")
        if (encrypted) args += listOf("-hls_key_info_file", File(dir, "keyinfo").path)
        args += File(dir, "v.m3u8").path
        sh(*args.toTypedArray())
        return File(dir, "v.m3u8")
    }

    @Test fun hlsAes128ToMp4(): Unit = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "hls" + System.nanoTime())
        val pl = makeHls(dir, encrypted = true)
        assertTrue(pl.readText().contains("METHOD=AES-128"))
        val (srv, base) = serve(dir)
        try {
            val dest = File(dir, "out.part")
            val item = DownloadItem("h1", "$base/v.m3u8", "v.mp4")
            val done = HlsDownloader(OkHttpClient()).download(item, dest, 4) {}
            assertEquals("v.mp4", done.fileName)
            val probe = sh("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height,nb_frames:format=duration,format_name", "-of", "compact", dest.path)
            val decodeErrors = sh("ffmpeg", "-v", "error", "-i", dest.path, "-f", "null", "-")
            log("HLS AES-128 (6 encrypted .ts segments, H.264 with B-frames + AAC) -> ${done.fileName}, ${dest.length() / 1024} KB\n  ffprobe: ${probe.trim().replace("\n", " | ")}\n  full decode errors: ${if (decodeErrors.isBlank()) "none" else decodeErrors.take(300)}")
            assertTrue(probe.contains("codec_name=h264") && probe.contains("codec_name=aac") && probe.contains("mp4"))
            assertTrue(probe.contains("width=640"))
            val dur = Regex("duration=([0-9.]+)").find(probe)!!.groupValues[1].toDouble()
            assertTrue("duration $dur", dur in 11.5..12.6)
            assertTrue(decodeErrors.isBlank())
            // Extract music from the same stream (AAC copied, no re-encode)
            val ts = File(dir, "all.ts")
            val hls2 = makeHls(File(dir, "plain"), encrypted = false)
            ts.outputStream().use { o -> hls2.readLines().filter { it.endsWith(".ts") }.forEach { File(hls2.parentFile, it).inputStream().use { s -> s.copyTo(o) } } }
            val m4a = File(dir, "music.m4a")
            ts.inputStream().use { TsToMp4.convert(it, m4a, audioOnly = true) }
            val ap = sh("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,codec_type:format=duration", "-of", "compact", m4a.path)
            log("Extract music from video (.ts): ${m4a.length() / 1024} KB m4a -> ${ap.trim().replace("\n", " | ")}")
            assertTrue(ap.contains("codec_name=aac") && !ap.contains("video"))
        } finally { srv.stop(0); dir.deleteRecursively() }
    }

    @Test fun drmStreamsRefused() {
        val hls = HlsDownloader(OkHttpClient())
        val cases = mapOf(
            "FairPlay" to "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://abc\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\na.ts\n",
            "Widevine" to "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,AAA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:4,\na.ts\n",
            "Session key" to "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n",
        )
        for ((name, text) in cases) {
            val e = runCatching { hls.parse("https://x.test/p.m3u8", text) { "" } }.exceptionOrNull()
            assertTrue(name, e is EncryptedStreamException)
            log("DRM check: $name stream -> \"${e!!.message}\"")
        }
        val ok = hls.parse("https://x.test/p.m3u8", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:4,\na.ts\n") { "" }
        assertEquals("https://x.test/k.bin", ok.segs[0].key!!.uri)
        assertEquals(7L, ok.segs[0].seq)
    }

    @Test fun linkCrawlerDepth(): Unit = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "crawl" + System.nanoTime()).apply { mkdirs() }
        File(dir, "index.html").writeText("""<a href="a.html">a</a><a href="top.zip">z</a><a href="https://other.example/x.html">ext</a>""")
        File(dir, "a.html").writeText("""<a href="b.html">b</a><a href="song.mp3">s</a><a href="pic.jpg">p</a>""")
        File(dir, "b.html").writeText("""<a href="deep.mp4">d</a>""")
        val (srv, base) = serve(dir)
        try {
            val c = LinkCrawler(OkHttpClient())
            val d1 = c.crawl("$base/index.html", LinkCrawler.Options(depth = 1)).map { it.name }
            val d2 = c.crawl("$base/index.html", LinkCrawler.Options(depth = 2)).map { it.name }
            val d3 = c.crawl("$base/index.html", LinkCrawler.Options(depth = 3)).map { it.name }
            val music = c.crawl("$base/index.html", LinkCrawler.Options(depth = 3, exts = LinkCrawler.PRESETS["Music"]!!)).map { it.name }
            log("Link grabber depth: 1 -> $d1, 2 -> $d2, 3 -> $d3, music filter -> $music (same-site on: other.example not crawled)")
            assertEquals(listOf("top.zip"), d1)
            assertEquals(setOf("top.zip", "song.mp3", "pic.jpg"), d2.toSet())
            assertTrue("deep.mp4" in d3)
            assertEquals(listOf("song.mp3"), music)
        } finally { srv.stop(0); dir.deleteRecursively() }
    }

    @Test fun passwordVaultEncryptsAndMatches() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val f = File.createTempFile("vault", ".bin")
        val v = PasswordVault(f, { key })
        v.save("https://accounts.example.com/login", "dev@example.com", "p@ss\"word'1")
        v.save("https://www.instagram.com/accounts/login/", "devraj", "hunter2")
        val raw = f.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("hunter2"))
        val v2 = PasswordVault(f, { key })
        assertEquals("devraj", v2.forSite("https://instagram.com/").single().username)
        assertEquals("p@ss\"word'1", v2.forSite("https://login.example.com").single().password)
        assertEquals("example.co.uk", PasswordVault.siteOf("https://shop.example.co.uk/x"))
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(PasswordVault(f, { wrongKey }).all().isEmpty())
        assertTrue(PasswordVault.fillScript("a", "p\"q").contains("\"p\\\"q\""))
        log("Password vault: AES-256-GCM file (${raw.size} bytes, no plain text inside), site matching (accounts.example.com = example.com), wrong key can't read it")
        f.delete()
    }

    @Test fun readingModeOnRealArticle() {
        val url = "https://en.wikipedia.org/wiki/BitTorrent"
        val html = OkHttpClient().newCall(Request.Builder().url(url).header("User-Agent", "CDMPlusTest/1.0 (test)").build()).execute().use { it.body!!.string() }
        val a = Reader.extract(url, html)!!
        log("Reading mode: '${a.title}' -> ${a.words} words of article text (menus, sidebars and scripts removed)")
        assertTrue(a.words > 1500)
        assertFalse(a.html.contains("<script"))
    }

    @Test fun seedRatioRule() {
        assertTrue(TorrentDownloader.shouldStopSeeding(150, 100, 1.5f, 0, 0))
        assertFalse(TorrentDownloader.shouldStopSeeding(149, 100, 1.5f, 0, 0))
        assertTrue(TorrentDownloader.shouldStopSeeding(0, 100, 0f, 30, 30 * 60_000L))
        assertFalse(TorrentDownloader.shouldStopSeeding(10_000, 100, 0f, 0, 999_999_999))
    }
}
