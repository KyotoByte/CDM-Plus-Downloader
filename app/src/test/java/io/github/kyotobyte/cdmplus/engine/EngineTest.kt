package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class EngineTest {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha(f: File) = sha(f.readBytes())
    private fun tmp(name: String) = File.createTempFile("cdm", name).also { it.delete() }

    @Test fun plan_splits_into_requested_segments() {
        val d = SegmentedDownloader(client)
        val p = d.plan(10_000_000, true, 8)
        assertEquals(8, p.size)
        assertEquals(0, p.first().start); assertEquals(9_999_999, p.last().end)
        for (i in 1 until p.size) assertEquals(p[i - 1].end + 1, p[i].start)
        assertEquals(1, d.plan(10_000_000, false, 8).size)
        assertEquals(1, d.plan(100_000, true, 8).size) // too small to split
        assertEquals(32, d.plan(100_000_000, true, 40).size) // capped at 32
    }

    @Test fun filename_parsing() {
        assertEquals("report final.pdf", SegmentedDownloader.fileNameFromDisposition("attachment; filename*=UTF-8''report%20final.pdf"))
        assertEquals("a.zip", SegmentedDownloader.fileNameFromDisposition("attachment; filename=\"a.zip\""))
        assertEquals("song.mp3", SegmentedDownloader.fileNameFromUrl("https://x.com/dl/song.mp3?token=1"))
    }

    @Test fun local_segmented_download_matches_checksum() = runBlocking<Unit> {
        val data = Random(7).nextBytes(6_000_000)
        TestServer(data, rangeSupport = true).use { srv ->
            val dest = tmp("seg")
            val d = SegmentedDownloader(client)
            val out = d.download(DownloadItem(id = "1", url = srv.url, fileName = "f", threads = 8), dest) {}
            assertEquals(Status.DONE, out.status)
            assertTrue(out.supportsRange)
            assertTrue(out.segments.size >= 8)
            assertEquals(sha(data), sha(dest))
            assertTrue("expected ranged requests", srv.rangedRequests.get() >= 9)
            dest.delete()
        }
    }

    @Test fun falls_back_to_single_connection_without_range() = runBlocking<Unit> {
        val data = Random(9).nextBytes(3_000_000)
        TestServer(data, rangeSupport = false).use { srv ->
            val dest = tmp("norange")
            val out = SegmentedDownloader(client).download(DownloadItem(id = "2", url = srv.url, fileName = "f", threads = 8), dest) {}
            assertFalse(out.supportsRange)
            assertEquals(1, out.segments.size)
            assertEquals(sha(data), sha(dest))
            dest.delete()
        }
    }

    @Test fun local_pause_and_resume_after_restart() = runBlocking<Unit> {
        val data = Random(11).nextBytes(4_000_000)
        TestServer(data, rangeSupport = true, bytesPerSecond = 600_000).use { srv ->
            val dest = tmp("resume")
            var last: DownloadItem? = null
            val d1 = SegmentedDownloader(client, progressIntervalMs = 100)
            val job = launch(Dispatchers.IO) {
                d1.download(DownloadItem(id = "3", url = srv.url, fileName = "f", threads = 4), dest) { last = it }
            }
            delay(1500); job.cancelAndJoin()
            val partial = last!!
            assertTrue(partial.downloadedBytes > 0 && partial.downloadedBytes < data.size)
            // "Restart": persist to JSON and load into a brand new engine instance.
            val json = Json { encodeDefaults = true }
            val restored = json.decodeFromString<DownloadItem>(json.encodeToString(partial))
            val d2 = SegmentedDownloader(client)
            val out = d2.download(restored, dest) {}
            assertEquals(sha(data), sha(dest))
            assertTrue("resume re-downloaded too much", d2.networkBytes.get() <= data.size - partial.downloadedBytes + 1)
            println("local resume: paused at ${partial.downloadedBytes}, resumed fetched ${d2.networkBytes.get()} of ${data.size}")
            assertEquals(Status.DONE, out.status)
            dest.delete()
        }
    }

    @Test fun queue_respects_max_simultaneous_and_completes() = runBlocking<Unit> {
        val data = Random(3).nextBytes(1_500_000)
        TestServer(data, rangeSupport = true, bytesPerSecond = 1_500_000).use { srv ->
            val dir = File(System.getProperty("java.io.tmpdir"), "cdmq" + System.nanoTime()).apply { mkdirs() }
            val published = mutableListOf<File>()
            val platform = object : DownloadPlatform {
                override fun partialDir() = dir
                override suspend fun publish(item: DownloadItem, file: File): Pair<String?, String?> {
                    val f = File(dir, item.fileName); file.renameTo(f); synchronized(published) { published += f }; return null to f.path
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val core = DownloadCore(scope, DownloadStore(File(dir, "db.json")), client, platform) { EngineSettings(threads = 4, maxSimultaneous = 2) }
            repeat(3) { core.add(srv.url, "f$it.bin") }
            delay(300)
            val running = core.items.value.count { it.status == Status.RUNNING }
            assertEquals(2, running)
            core.awaitIdle()
            assertTrue(core.items.value.all { it.status == Status.DONE })
            published.forEach { assertEquals(sha(data), sha(it)) }
            // Persistence: a new core sees all three as done.
            delay(1000)
            val core2 = DownloadCore(scope, DownloadStore(File(dir, "db.json")), client, platform) { EngineSettings() }
            assertEquals(3, core2.items.value.count { it.status == Status.DONE })
            dir.deleteRecursively()
        }
    }

    // ---- Live tests against a real public speed-test file (OVH, 10 MiB) ----
    private val live = System.getProperty("cdm.live.url") ?: "http://ipv4.download.thinkbroadband.com/10MB.zip"

    @Test fun live_segmented_download_and_resume_checksum() = runBlocking<Unit> {
        val reference = sha((1..4).firstNotNullOf { n -> runCatching { client.newCall(okhttp3.Request.Builder().url(live).build()).execute().use { it.body!!.bytes() } }.getOrElse { Thread.sleep(2000L * n); if (n == 4) throw it; null } })
        val d = SegmentedDownloader(client)
        val probe = d.probe(live)
        println("live probe: length=${probe.length} ranges=${probe.acceptsRanges}")
        assertTrue(probe.acceptsRanges)
        val len = probe.length
        assertTrue(len > 1_000_000)

        // 1) full 8-thread segmented download
        val dest = tmp("live")
        val t0 = System.currentTimeMillis()
        val out = d.download(DownloadItem(id = "L1", url = live, fileName = "10Mb.dat", threads = 8), dest) {}
        println("live segmented: ${out.segments.size} parts (${d.steals.get()} dynamic splits) in ${System.currentTimeMillis() - t0} ms")
        assertTrue(out.segments.size >= 8)
        assertEquals(reference, sha(dest))
        dest.delete()

        // 2) pause mid-way (speed-limited so we can catch it), then resume in a new engine
        val dest2 = tmp("live2")
        var last: DownloadItem? = null
        val slow = SegmentedDownloader(client, SpeedLimiter(1_500_000), progressIntervalMs = 100)
        val job = async(Dispatchers.IO) {
            slow.download(DownloadItem(id = "L2", url = live, fileName = "10Mb.dat", threads = 6), dest2) { last = it }
        }
        delay(2500); job.cancelAndJoin()
        val partial = last!!
        println("live paused at ${partial.downloadedBytes} bytes")
        assertTrue(partial.downloadedBytes in 1 until len)
        val restored = Json.decodeFromString<DownloadItem>(Json { encodeDefaults = true }.encodeToString(partial))
        val fast = SegmentedDownloader(client)
        val done = fast.download(restored, dest2) {}
        println("live resumed: fetched ${fast.networkBytes.get()} more bytes")
        assertEquals(Status.DONE, done.status)
        assertEquals(reference, sha(dest2))
        assertTrue(fast.networkBytes.get() <= len - partial.downloadedBytes + 1)
        println("live sha256 = $reference")
        dest2.delete()
    }

    @Test fun work_stealing_splits_slow_tail() = runBlocking<Unit> {
        val data = Random(5).nextBytes(8_000_000)
        TestServer(data, rangeSupport = true, bytesPerSecond = 1_000_000, slowFrom = 6_000_000).use { srv ->
            val dest = tmp("steal")
            val d = SegmentedDownloader(client)
            val out = d.download(DownloadItem(id = "S", url = srv.url, fileName = "f", threads = 4), dest) {}
            println("work stealing: ${d.steals.get()} splits, ${out.segments.size} parts")
            assertTrue(d.steals.get() > 0)
            assertEquals(sha(data), sha(dest))
            assertEquals(data.size.toLong(), out.segments.sumOf { it.done })
            dest.delete()
        }
    }

    /** Real-world speed: 1 connection vs 8 vs 16 separate HTTP/1.1 connections. */
    @Test fun live_benchmark_single_vs_multi() = runBlocking<Unit> {
        val url = System.getProperty("cdm.bench.url") ?: "http://ipv4.download.thinkbroadband.com/50MB.zip"
        val h1 = OkHttpClient.Builder().dns(Ipv4FirstDns).protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val h2 = OkHttpClient.Builder().dns(Ipv4FirstDns).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val results = mutableListOf<String>()
        var ref: String? = null
        for ((label, c, threads) in listOf(Triple("1 connection", h1, 1), Triple("8 connections", h1, 8), Triple("16 connections", h1, 16))) {
            val dest = tmp("bench")
            val d = SegmentedDownloader(c)
            val t0 = System.nanoTime()
            val out = d.download(DownloadItem(id = "B", url = url, fileName = "b", threads = threads), dest) {}
            val secs = (System.nanoTime() - t0) / 1e9
            val mbps = out.totalBytes / 1048576.0 / secs
            val s = sha(dest)
            if (ref == null) ref = s else assertEquals(ref, s)
            results += "%-30s %6.2f s  %7.2f MB/s  (%d parts, %d splits)".format(label, secs, mbps, out.segments.size, d.steals.get())
            dest.delete()
        }
        println("BENCHMARK $url\n" + results.joinToString("\n"))
        File(System.getProperty("cdm.bench.out") ?: "build/benchmark.txt").apply { parentFile?.mkdirs() }.writeText("BENCHMARK $url\n" + results.joinToString("\n") + "\n")
    }
}
