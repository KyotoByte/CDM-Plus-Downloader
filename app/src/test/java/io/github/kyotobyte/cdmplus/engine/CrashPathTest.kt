package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Build 8: every bad input must end as a clear error, never a crash or an endless retry. */
class CrashPathTest {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private fun tmp() = File.createTempFile("cdmcrash", ".bin").also { it.delete() }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** Raw server: [respond] gets the request path and writes the whole HTTP response. */
    private class Raw(val respond: (String, java.io.OutputStream) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val url get() = "http://127.0.0.1:${server.localPort}"
        init {
            pool.execute {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    pool.execute { s.use { runCatching {
                        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val line = r.readLine() ?: return@runCatching
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        respond(line.split(" ")[1], s.getOutputStream()); s.getOutputStream().flush()
                    } } }
                }
            }
        }
        override fun close() { server.close(); pool.shutdownNow() }
    }

    private suspend fun expectFail(url: String, dest: File = tmp()): Throwable {
        try {
            SegmentedDownloader(client).download(DownloadItem(id = "x", url = url, fileName = "f", threads = 4), dest) {}
        } catch (e: Throwable) { return e }
        fail("expected a failure for $url"); throw IllegalStateException()
    }

    @Test fun emptyAndGarbageUrls() = runBlocking<Unit> {
        for (u in listOf("", "   ", "garbage", "htp:/x", "javascript:alert(1)", "http://", "https://exa mple.com/a b", "content://x/y", "\u0000\u0001")) {
            val e = expectFail(u)
            assertTrue("$u -> $e", e is PermanentException)
            assertFalse(DownloadErrors.retryable(e))
            assertEquals("Not a valid web link", DownloadErrors.message(e))
        }
    }

    @Test fun noNetwork() = runBlocking<Unit> {
        val e = expectFail("http://no-such-host.invalid/file.bin")
        assertTrue(e is UnknownHostException)
        assertTrue("no network is retried", DownloadErrors.retryable(e))
        assertEquals("No internet / host not found", DownloadErrors.message(e))
        // Connection refused (server down)
        val port = ServerSocket(0).use { it.localPort }
        val e2 = expectFail("http://127.0.0.1:$port/f")
        assertTrue(e2 is IOException); assertTrue(DownloadErrors.retryable(e2))
    }

    @Test fun zeroByteResponse() = runBlocking<Unit> {
        Raw { _, o -> o.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }.use { s ->
            val e = expectFail(s.url + "/empty")
            assertTrue("$e", e is PermanentException); assertTrue(e.message!!.contains("empty"))
            assertFalse(DownloadErrors.retryable(e))
        }
        // Server that ignores Range and also sends nothing, unknown length: completes as a 0-byte file, no crash.
        Raw { _, o -> o.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }.use { s ->
            val e = expectFail(s.url + "/empty")
            assertTrue(e is HttpStatusException); assertFalse(DownloadErrors.retryable(e))
            assertTrue(DownloadErrors.message(e).contains("416"))
        }
    }

    @Test fun redirectLoop() = runBlocking<Unit> {
        Raw { p, o -> val next = if (p == "/a") "/b" else "/a"; o.write("HTTP/1.1 302 Found\r\nLocation: $next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }.use { s ->
            val e = expectFail(s.url + "/a")
            assertTrue("$e", DownloadErrors.isRedirectLoop(e))
            assertFalse("a redirect loop is not retried forever", DownloadErrors.retryable(e))
            assertTrue(DownloadErrors.message(e).contains("redirecting"))
        }
    }

    @Test fun hugeContentLength() = runBlocking<Unit> {
        val huge = 9_000_000_000_000_000L // ~8 EB
        Raw { _, o -> o.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-0/$huge\r\nAccept-Ranges: bytes\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray()) }.use { s ->
            val dest = tmp()
            val e = expectFail(s.url + "/big", dest)
            assertTrue("$e", e is StorageException)
            assertTrue(e.message!!.startsWith("Not enough free storage"))
            assertFalse(DownloadErrors.retryable(e))
            assertFalse("nothing preallocated", dest.exists() && dest.length() > 0)
        }
        // Lying Content-Length without Range support: body shorter than promised -> clear error, no hang.
        Raw { _, o -> o.write("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\nConnection: close\r\n\r\nshort".toByteArray()) }.use { s ->
            val e = expectFail(s.url + "/lie")
            assertTrue("$e", e is IOException)
        }
    }

    @Test fun diskFullOrIoError() = runBlocking<Unit> {
        // IO error: destination inside a path that is a FILE, so nothing can be written there.
        val blocker = tmp().apply { writeText("x") }
        val data = Random(5).nextBytes(300_000)
        TestServer(data, rangeSupport = true).use { srv ->
            val e = expectFail(srv.url, File(blocker, "sub/out.bin"))
            assertTrue("$e", e is IOException)
        }
        blocker.delete()
        // ENOSPC as Android reports it is recognised anywhere in the cause chain and never auto-retried.
        val enospc = IOException("write failed: ENOSPC (No space left on device)")
        assertTrue(DownloadErrors.isStorage(RuntimeException("wrap", enospc)))
        assertFalse(DownloadErrors.retryable(enospc))
        assertTrue(DownloadErrors.message(enospc).contains("storage is full"))
    }

    @Test fun invalidTorrentAndMagnet() {
        assertTrue(TorrentDownloader.isValidMagnet("magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056&dn=Cosmos"))
        assertTrue(TorrentDownloader.isValidMagnet("magnet:?dn=x&xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U"))
        for (m in listOf("magnet:", "magnet:?", "magnet:?dn=abc", "magnet:?xt=urn:btih:zz", "magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d5305", "http://x/y.torrent"))
            assertFalse(m, TorrentDownloader.isValidMagnet(m))
        assertFalse(TorrentDownloader.looksLikeTorrent(ByteArray(0)))
        assertFalse(TorrentDownloader.looksLikeTorrent("<html>404 not found</html>".repeat(4).toByteArray()))
        assertFalse(TorrentDownloader.looksLikeTorrent(Random(1).nextBytes(2000)))
        assertTrue(TorrentDownloader.looksLikeTorrent("d8:announce30:http://tracker.example/announce4:infod6:lengthi1e4:name1:a12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaaee".toByteArray()))
        // Through the engine: garbage .torrent and hashless magnet fail with a message, without touching the native library.
        val dir = File(System.getProperty("java.io.tmpdir"), "cdmtor" + System.nanoTime()).apply { mkdirs() }
        val td = TorrentDownloader(dir, SpeedLimiter())
        val bad = File(dir, "bad.torrent").apply { writeText("<html>not a torrent</html>") }
        for (u in listOf("magnet:?dn=nohash", bad.toURI().toString())) {
            val e = runCatching { runBlocking { td.files(DownloadItem(id = "t" + u.hashCode(), url = u, fileName = "t"), client) } }.exceptionOrNull()
            assertNotNull(u, e)
            assertTrue("$u -> $e", e is PermanentException)
        }
        dir.deleteRecursively()
        assertEquals("Torrents not supported on this CPU", TorrentDownloader.UNSUPPORTED_CPU)
    }

    @Test fun processDeathMidDownloadResumes() = runBlocking<Unit> {
        val data = Random(21).nextBytes(3_000_000)
        TestServer(data, rangeSupport = true, bytesPerSecond = 150_000).use { srv ->
            val dir = File(System.getProperty("java.io.tmpdir"), "cdmdeath" + System.nanoTime()).apply { mkdirs() }
            val out = File(dir, "out").apply { mkdirs() }
            val platform = object : DownloadPlatform {
                override fun partialDir() = dir
                override suspend fun publish(item: DownloadItem, file: File): Pair<String?, String?> {
                    val f = File(out, item.fileName); file.renameTo(f); return null to f.path
                }
            }
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val core1 = DownloadCore(scope1, DownloadStore(File(dir, "db.json")), client, platform) { EngineSettings(threads = 4) }
            core1.add(srv.url, "movie.bin")
            delay(2000)
            val mid = core1.items.value.single()
            assertTrue("was mid-download: ${mid.downloadedBytes}", mid.downloadedBytes in 1 until data.size)
            scope1.cancel() // the process is killed: nothing runs any more
            delay(1200)
            // New process: the app object builds a new core from the same store and starts the queue.
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val core2 = DownloadCore(scope2, DownloadStore(File(dir, "db.json")), client, platform) { EngineSettings(threads = 4) }
            core2.start()
            delay(500)
            val st = core2.items.value.single().status
            if (st == Status.PAUSED || st == Status.FAILED) core2.resume(core2.items.value.single().id)
            core2.awaitIdle(60_000)
            val done = core2.items.value.single()
            assertEquals(done.error ?: "", Status.DONE, done.status)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, sha(File(out, done.fileName)))
            println("process death: killed at ${mid.downloadedBytes} of ${data.size}, after restart status was $st, finished with matching SHA-256")
            scope2.cancel(); dir.deleteRecursively()
        }
    }
}
