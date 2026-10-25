package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class EncryptedStreamException : IOException("Protected content, can't be downloaded")

/**
 * Downloads HLS playlists: picks the best variant of a master playlist, fetches media
 * segments in parallel, decrypts AES-128 segments (keys the server hands out openly; not DRM),
 * and converts MPEG-TS into one .mp4 with CDM+'s own remuxer (no ffmpeg). fMP4 streams are
 * joined into .mp4 directly. DRM streams (SAMPLE-AES / FairPlay / Widevine key formats) are refused.
 */
class HlsDownloader(private val client: OkHttpClient, private val limiter: SpeedLimiter = SpeedLimiter()) {

    data class Key(val uri: String, val iv: ByteArray?)
    data class Seg(val url: String, val key: Key?, val seq: Long)
    data class MediaPlaylist(val segs: List<Seg>, val initSegment: String?) {
        val segments: List<String> get() = segs.map { it.url }
    }

    private fun get(url: String, headers: Map<String, String>): String = String(getBytes(url, headers), Charsets.UTF_8)

    private fun getBytes(url: String, headers: Map<String, String>): ByteArray {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (!headers.keys.any { it.equals("User-Agent", true) }) b.header("User-Agent", SegmentedDownloader.DEFAULT_UA)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolve(base: String, ref: String): String = base.toHttpUrl().resolve(ref)?.toString() ?: ref

    companion object {
        private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        fun attrs(line: String): Map<String, String> = ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

        /** True when a key line means real DRM (not openly served AES-128). */
        fun isDrmKey(line: String): Boolean {
            val a = attrs(line)
            val method = a["METHOD"]?.uppercase() ?: "NONE"
            if (method == "NONE") return false
            if (method != "AES-128") return true // SAMPLE-AES, SAMPLE-AES-CTR, ISO-23001-7 ...
            val fmt = a["KEYFORMAT"]?.lowercase()
            if (fmt != null && fmt != "identity") return true
            val uri = a["URI"]?.lowercase() ?: return true
            return uri.startsWith("skd:") || uri.startsWith("data:text/plain;base64") && fmt != null
        }

        fun ivFor(seg: Seg): ByteArray = seg.key?.iv ?: ByteArray(16).also { b -> var s = seg.seq; for (i in 15 downTo 8) { b[i] = (s and 0xFF).toByte(); s = s shr 8 } }

        fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            return c.doFinal(data)
        }

        private fun hex(s: String): ByteArray {
            val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            return ByteArray(16) { ((Character.digit(h[it * 2], 16) shl 4) or Character.digit(h[it * 2 + 1], 16)).toByte() }
        }
    }

    fun parse(baseUrl: String, text: String, fetch: (String) -> String): MediaPlaylist {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "#EXTM3U") throw IOException("Not an HLS playlist")
        lines.filter { it.startsWith("#EXT-X-SESSION-KEY") }.forEach { if (isDrmKey(it)) throw EncryptedStreamException() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            // Master playlist: choose the highest bandwidth variant.
            var best: String? = null; var bestBw = -1L
            lines.forEachIndexed { i, l ->
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") }
                    if (uri != null && bw > bestBw) { bestBw = bw; best = uri }
                }
            }
            val variant = resolve(baseUrl, best ?: throw IOException("No playable variant"))
            return parse(variant, fetch(variant), fetch)
        }
        var seq = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        var key: Key? = null
        var init: String? = null
        val segs = ArrayList<Seg>()
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-KEY") -> {
                    if (isDrmKey(l)) throw EncryptedStreamException()
                    val a = attrs(l)
                    key = if ((a["METHOD"] ?: "NONE").equals("NONE", true)) null else Key(resolve(baseUrl, a["URI"] ?: throw IOException("Encrypted stream without a key link")), a["IV"]?.let { hex(it) })
                }
                l.startsWith("#EXT-X-MAP") -> if (init == null) init = attrs(l)["URI"]?.let { resolve(baseUrl, it) }
                !l.startsWith("#") -> { segs.add(Seg(resolve(baseUrl, l), key, seq)); seq++ }
            }
        }
        if (segs.isEmpty()) throw IOException("Playlist has no segments")
        return MediaPlaylist(segs, init)
    }

    suspend fun download(item: DownloadItem, dest: File, parallel: Int, onProgress: suspend (DownloadItem) -> Unit): DownloadItem = withContext(Dispatchers.IO) {
        val pl = parse(item.url, get(item.url, item.headers)) { get(it, item.headers) }
        val partsDir = File(dest.parentFile, dest.name + ".hls").apply { mkdirs() }
        val all = listOfNotNull(pl.initSegment?.let { Seg(it, null, -1) }) + pl.segs
        val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val doneCount = AtomicInteger(all.indices.count { File(partsDir, "$it.seg").exists() })
        val bytes = AtomicLong(partsDir.listFiles()?.filter { it.name.endsWith(".seg") }?.sumOf { it.length() } ?: 0)
        var cur = item.copy(hlsTotal = all.size, hlsDone = doneCount.get(), downloadedBytes = bytes.get(), totalBytes = -1)
        onProgress(cur)
        val sem = Semaphore(parallel.coerceIn(1, 16))
        coroutineScope {
            all.mapIndexed { i, seg ->
                async {
                    val out = File(partsDir, "$i.seg")
                    if (out.exists()) return@async
                    sem.withPermit {
                        var attempt = 0
                        while (true) {
                            coroutineContext.ensureActive()
                            try {
                                fetchTo(seg.url, item.headers, File(partsDir, "$i.tmp"), out, bytes)
                                seg.key?.let { k ->
                                    val kb = keys.getOrPut(k.uri) { getBytes(k.uri, item.headers).also { if (it.size != 16) throw EncryptedStreamException() } }
                                    out.writeBytes(decrypt(out.readBytes(), kb, ivFor(seg)))
                                }
                                break
                            } catch (e: EncryptedStreamException) { out.delete(); throw e }
                            catch (e: javax.crypto.BadPaddingException) { out.delete(); throw IOException("Couldn't decrypt the stream (wrong key)") }
                            catch (e: IOException) { out.delete(); if (++attempt > 5) throw e; kotlinx.coroutines.delay(600L * attempt) }
                        }
                        doneCount.incrementAndGet()
                        onProgress(cur.copy(hlsDone = doneCount.get(), downloadedBytes = bytes.get()))
                    }
                }
            }.awaitAll()
        }
        val parts = all.indices.map { File(partsDir, "$it.seg") }
        var name = item.fileName
        val isTs = pl.initSegment == null && parts.firstOrNull()?.let { f -> f.inputStream().use { it.read() } == 0x47 } == true
        var converted = false
        if (isTs) {
            // TS -> MP4 without re-encoding, straight from the segment files (no extra joined copy).
            try {
                val seq = java.io.SequenceInputStream(java.util.Collections.enumeration(parts.map { it.inputStream() }))
                seq.use { TsToMp4.convert(it, dest) }
                converted = true
                name = name.substringBeforeLast('.') + ".mp4"
            } catch (e: IOException) { dest.delete() }
        }
        if (!converted) {
            dest.outputStream().buffered(1 shl 20).use { o -> parts.forEach { f -> f.inputStream().use { s -> s.copyTo(o, 1 shl 16) } } }
            name = name.substringBeforeLast('.') + if (pl.initSegment != null) ".mp4" else ".ts"
        }
        partsDir.deleteRecursively()
        cur = cur.copy(fileName = name, mimeType = if (name.endsWith(".mp4")) "video/mp4" else "video/mp2t", hlsDone = all.size, totalBytes = dest.length(), downloadedBytes = dest.length(), status = Status.DONE, finishedAt = System.currentTimeMillis(), error = null)
        cur
    }

    private suspend fun fetchTo(url: String, headers: Map<String, String>, tmp: File, out: File, bytes: AtomicLong) {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (!headers.keys.any { it.equals("User-Agent", true) }) b.header("User-Agent", SegmentedDownloader.DEFAULT_UA)
        val call = client.newCall(b.build())
        val h = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code)
                val input = r.body?.byteStream() ?: throw IOException("Empty segment")
                var got = 0L
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        limiter.acquire(n); o.write(buf, 0, n); got += n
                    }
                }
                if (!tmp.renameTo(out)) throw IOException("rename failed")
                bytes.addAndGet(got)
            }
        } finally { h.dispose() }
    }
}
