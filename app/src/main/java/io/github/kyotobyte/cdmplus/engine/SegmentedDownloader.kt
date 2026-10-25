package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class HttpStatusException(val code: Int) : IOException("Server answered HTTP $code")
internal class RangeIgnoredException : IOException("Server ignored the Range header")

/**
 * CDM+ download engine, written from scratch.
 *
 * - Probes the URL with `Range: bytes=0-0` to learn size and Range support.
 * - Splits the file into parts and fetches them over separate connections
 *   (1-32), each worker writing at its own offset with positional FileChannel writes
 *   into a preallocated file.
 * - Dynamic splitting (work stealing): when a worker runs out of parts, it splits the
 *   largest remaining part in half and takes the second half, so all connections stay
 *   busy until the very end instead of waiting on one slow part.
 * - Retries stalled/broken parts from where they stopped.
 * - Falls back to one connection when the server has no Range support.
 * - Progress is reported as a full [DownloadItem] snapshot (parts included),
 *   so the caller can persist it and resume later, even after a process restart.
 */
class SegmentedDownloader(
    private val client: OkHttpClient,
    private val limiter: SpeedLimiter = SpeedLimiter(),
    private val minSegmentBytes: Long = 512 * 1024L,
    private val progressIntervalMs: Long = 400,
    private val workStealing: Boolean = true,
) {
    data class Probe(
        val length: Long,
        val acceptsRanges: Boolean,
        val fileName: String?,
        val mime: String?,
        val etag: String?,
        val finalUrl: String,
        val disposition: String? = null,
    )

    /** Bytes actually received from the network by this instance (used by tests). */
    val networkBytes = AtomicLong(0)
    /** How many times a part was split to keep connections busy. */
    val steals = AtomicLong(0)

    /** Live, mutable part. [end] can shrink when another worker steals the tail. */
    private class Part(val index: Int, val start: Long, @Volatile var end: Long, done: Long) {
        @Volatile var done: Long = done
        @Volatile var taken = false
        val position get() = start + done
        val remaining get() = if (end < 0) Long.MAX_VALUE else end - position + 1
        fun toSegment() = Segment(index, start, end, done)
    }

    private fun request(url: String, headers: Map<String, String>, range: String?): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.header(k, v) }
        if (!headers.keys.any { it.equals("User-Agent", true) }) b.header("User-Agent", DEFAULT_UA)
        if (range != null) b.header("Range", range)
        b.header("Accept-Encoding", "identity")
        return b.build()
    }

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Probe = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers, "bytes=0-0")).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            val cd = r.header("Content-Disposition")
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()
            val etag = r.header("ETag") ?: r.header("Last-Modified")
            val finalUrl = r.request.url.toString()
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1
                Probe(total, total > 0, fileNameFromDisposition(cd), mime, etag, finalUrl, cd)
            } else {
                // A 200 to a ranged request means the server ignored Range.
                val len = r.header("Content-Length")?.toLongOrNull() ?: -1
                Probe(len, false, fileNameFromDisposition(cd), mime, etag, finalUrl, cd)
            }
        }
    }

    /** Builds the initial part plan for a fresh download. */
    fun plan(length: Long, acceptsRanges: Boolean, threads: Int): List<Segment> {
        if (length <= 0 || !acceptsRanges || threads <= 1 || length < minSegmentBytes * 2) {
            return listOf(Segment(0, 0, if (length > 0) length - 1 else -1))
        }
        val n = minOf(threads.coerceIn(1, MAX_THREADS).toLong(), length / minSegmentBytes).toInt().coerceAtLeast(1)
        val size = length / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) length - 1 else start + size - 1
            Segment(i, start, end)
        }
    }

    /**
     * Downloads (or resumes) [item] into [dest]. Returns the finished item with status DONE.
     * On cancellation the last snapshot has already been delivered to [onProgress].
     */
    suspend fun download(
        item: DownloadItem,
        dest: File,
        onProgress: suspend (DownloadItem) -> Unit,
    ): DownloadItem = withContext(Dispatchers.IO) {
        var current = item
        val scheme = current.url.substringBefore("://", "").lowercase()
        if ((scheme != "http" && scheme != "https") || current.url.toHttpUrlOrNull() == null) {
            throw PermanentException("Not a valid web link")
        }
        val fresh = current.segments.isEmpty() || !dest.exists()
        if (fresh) {
            val p = probe(current.url, current.headers)
            if (p.length == 0L) throw PermanentException("The server sent an empty file (0 bytes)")
            if (p.length > 0) {
                dest.parentFile?.mkdirs()
                val free = (dest.parentFile ?: dest.absoluteFile.parentFile)?.usableSpace ?: 0L
                if (free > 0 && p.length > free - RESERVE_BYTES) {
                    throw StorageException("Not enough free storage: needs ${DownloadErrors.humanBytes(p.length)}, ${DownloadErrors.humanBytes(free)} free")
                }
            }
            val betterName = FileNamer.improve(current.fileName, p.disposition, p.finalUrl, p.mime)
            current = current.copy(
                fileName = betterName,
                totalBytes = p.length,
                supportsRange = p.acceptsRanges,
                mimeType = (current.mimeType?.takeUnless { FileNamer.extFromMime(it) == null } ?: p.mime?.takeUnless { FileNamer.extFromMime(it) == null })
                    ?: FileNamer.extToMime[FileNamer.ext(betterName)] ?: current.mimeType ?: p.mime,
                etag = p.etag,
                segments = plan(p.length, p.acceptsRanges, current.threads),
                downloadedBytes = 0,
            )
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            // Preallocate so parallel writers never extend the file.
            try {
                RandomAccessFile(dest, "rw").use { raf -> if (p.length > 0 && p.acceptsRanges) raf.setLength(p.length) }
            } catch (e: IOException) {
                dest.delete()
                throw StorageException("Couldn't reserve space for this file (${e.message})", e)
            }
        } else if (!current.supportsRange) {
            // Server can't resume: start the single connection over.
            current = current.copy(segments = plan(current.totalBytes, false, 1), downloadedBytes = 0)
            RandomAccessFile(dest, "rw").use { it.setLength(0) }
        }
        onProgress(current)

        try {
            current = runParts(current, dest, onProgress)
        } catch (e: RangeIgnoredException) {
            // Fallback: the server answered 200 to a ranged request. Use one connection.
            current = current.copy(supportsRange = false, segments = plan(current.totalBytes, false, 1), downloadedBytes = 0)
            RandomAccessFile(dest, "rw").use { it.setLength(0) }
            onProgress(current)
            current = runParts(current, dest, onProgress)
        }

        if (current.totalBytes > 0 && dest.length() != current.totalBytes) {
            throw IOException("Size mismatch: got ${dest.length()} of ${current.totalBytes} bytes")
        }
        val total = if (current.totalBytes > 0) current.totalBytes else dest.length()
        current.copy(status = Status.DONE, totalBytes = total, downloadedBytes = total, finishedAt = System.currentTimeMillis(), error = null)
    }

    private suspend fun runParts(
        start: DownloadItem,
        dest: File,
        onProgress: suspend (DownloadItem) -> Unit,
    ): DownloadItem = coroutineScope {
        val lock = Any()
        val parts = start.segments.map { Part(it.index, it.start, it.end, it.done) }.toMutableList()
        val canSteal = workStealing && start.supportsRange && start.totalBytes > 0
        val perItem = if (start.speedLimitBytes > 0) SpeedLimiter(start.speedLimitBytes) else null
        val workers = if (start.supportsRange && start.totalBytes > 0) start.threads.coerceIn(1, MAX_THREADS) else 1

        fun snapshot(): DownloadItem {
            val segs = synchronized(lock) { parts.map { it.toSegment() } }
            return start.copy(segments = segs, downloadedBytes = segs.sumOf { it.done.coerceAtMost(if (it.length >= 0) it.length else Long.MAX_VALUE) })
        }

        /** Next part to work on: an untaken unfinished one, else steal half of the largest. */
        fun nextPart(): Part? = synchronized(lock) {
            parts.firstOrNull { !it.taken && it.remaining > 0 }?.let { it.taken = true; return it }
            if (!canSteal) return null
            val victim = parts.filter { it.taken && it.end >= 0 }.maxByOrNull { it.remaining } ?: return null
            if (victim.remaining < minSegmentBytes * 2) return null
            val splitAt = victim.position + victim.remaining / 2
            val stolen = Part(parts.size, splitAt, victim.end, 0).also { it.taken = true }
            victim.end = splitAt - 1
            parts += stolen
            steals.incrementAndGet()
            stolen
        }

        val ticker = launch {
            while (isActive) {
                delay(progressIntervalMs)
                onProgress(snapshot())
            }
        }
        try {
            (0 until workers).map {
                async {
                    while (true) {
                        coroutineContext.ensureActive()
                        val part = nextPart() ?: break
                        fetchPart(start, part, parts.size == 1 && !canSteal, dest, lock, perItem)
                    }
                }
            }.awaitAll()
        } catch (e: Throwable) {
            ticker.cancel()
            withContext(NonCancellable) { onProgress(snapshot()) }
            throw e
        }
        ticker.cancel()
        val last = snapshot()
        onProgress(last)
        last
    }

    private suspend fun fetchPart(item: DownloadItem, part: Part, single: Boolean, dest: File, lock: Any, perItem: SpeedLimiter?) {
        var attempt = 0
        while (part.remaining > 0) {
            coroutineContext.ensureActive()
            val before = part.done
            try {
                fetchOnce(item, part, single, dest, lock, perItem)
                if (part.end < 0) return // unknown length: server closed = done
            } catch (e: CancellationException) {
                throw e
            } catch (e: RangeIgnoredException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                if (++attempt > MAX_RETRIES) throw e
                delay(700L * attempt)
            } catch (e: IOException) {
                if (DownloadErrors.isStorage(e)) throw StorageException("Phone storage is full - free some space, then resume", e)
                if (e is PermanentException || DownloadErrors.isRedirectLoop(e)) throw e
                if (part.done > before) attempt = 0 // made progress: a stall, not a dead link
                if (++attempt > MAX_RETRIES) throw e
                delay(500L * attempt)
            }
        }
    }

    private suspend fun fetchOnce(item: DownloadItem, part: Part, single: Boolean, dest: File, lock: Any, perItem: SpeedLimiter?) {
        val from = part.position
        val range = when {
            part.end >= 0 && item.supportsRange -> "bytes=$from-${part.end}"
            part.done > 0 && item.supportsRange -> "bytes=$from-"
            else -> null
        }
        val call = client.newCall(request(item.url, item.headers, range))
        val handle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code)
                if (range != null && r.code == 200) {
                    if (!single) throw RangeIgnoredException()
                    part.done = 0 // whole file again: start over at 0
                } else if (range == null && part.done > 0) {
                    part.done = 0
                }
                val body = r.body ?: throw IOException("Empty body")
                RandomAccessFile(dest, "rw").use { raf ->
                    val ch = raf.channel
                    val buf = ByteArray(BUFFER)
                    val input = body.byteStream()
                    while (true) {
                        coroutineContext.ensureActive()
                        val pos = part.position
                        val room = part.remaining
                        if (room <= 0) break // reached our (possibly shrunk) end
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), room).toInt())
                        if (n < 0) break
                        limiter.acquire(n)
                        perItem?.acquire(n)
                        // Clamp to the current end (it may have been stolen meanwhile).
                        val allowed = synchronized(lock) { minOf(n.toLong(), part.remaining).toInt() }
                        if (allowed <= 0) break
                        val bb = ByteBuffer.wrap(buf, 0, allowed)
                        var p = pos
                        while (bb.hasRemaining()) p += ch.write(bb, p)
                        part.done += allowed
                        networkBytes.addAndGet(n.toLong())
                    }
                }
                if (part.end >= 0 && part.remaining > 0) throw IOException("Connection closed early on part ${part.index}")
            }
        } finally {
            handle.dispose()
        }
    }

    companion object {
        const val MAX_RETRIES = 6
        /** Keep this much free so the phone itself never runs out. */
        const val RESERVE_BYTES = 64L * 1024 * 1024
        const val MAX_THREADS = 32
        const val BUFFER = 256 * 1024
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 CDMPlus/1.0"

        fun fileNameFromDisposition(cd: String?): String? {
            if (cd.isNullOrBlank()) return null
            Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8") }.getOrNull()
            }
            Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
            return null
        }

        fun fileNameFromUrl(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val last = path.substringAfterLast('/')
            val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
            return sanitize(decoded.ifBlank { "download" })
        }

        /** "a/../b:c/" -> "a/b_c": safe relative folder path, null when empty. */
        fun sanitizeDir(dir: String?): String? = dir?.split('/', '\\')?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "." && it != ".." }?.map { sanitize(it).take(100).trim('.', ' ').ifBlank { "_" } }
            ?.take(16)?.joinToString("/")?.ifEmpty { null }

        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(180).ifBlank { "download" }
    }
}
