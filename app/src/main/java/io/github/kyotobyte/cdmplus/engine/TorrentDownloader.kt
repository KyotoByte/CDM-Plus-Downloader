package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Torrents and magnet links via libtorrent4j (MIT, wrapping libtorrent, BSD). Each torrent is
 * downloaded into its own work folder; pausing removes it from the session but keeps the data,
 * and resuming re-checks what is already there, so nothing is fetched twice.
 * You can pick which files inside a torrent to download, and optionally keep seeding
 * after it finishes until a share ratio (uploaded / downloaded) or a time limit is reached.
 */
class TorrentDownloader(private val workDir: File, private val limiter: SpeedLimiter) {
    private var session: SessionManager? = null

    @Synchronized
    fun session(): SessionManager {
        session?.let { return it }
        val s = try { SessionManager(false).also { it.start() } } catch (e: Throwable) {
            // UnsatisfiedLinkError / LinkageError: no native torrent library for this CPU
            throw IOException(UNSUPPORTED_CPU)
        }
        session = s
        return s
    }


    /** False on phones without the native library (e.g. 32-bit x86). */
    fun available(): Boolean = runCatching { session(); true }.getOrDefault(false)

    fun stop() { synchronized(this) { session?.stop(); session = null } }

    private fun info(item: DownloadItem, dir: File, client: OkHttpClient): TorrentInfo {
        val cached = File(dir, "meta.torrent")
        if (cached.length() > 0) runCatching { return TorrentInfo(cached) }.onFailure { cached.delete() }
        val url = item.url
        if (url.startsWith("magnet:", true) && !isValidMagnet(url)) throw PermanentException("Not a valid magnet link (it has no torrent hash)")
        val bytes: ByteArray = when {
            url.startsWith("magnet:", true) -> session().fetchMagnet(url, 90, dir)
                ?: throw IOException("No peers sent the torrent info in 90 s (magnet link). Try again later.")
            url.startsWith("file:", true) -> File(URI(url)).readBytes()
            else -> client.newCall(Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*item.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())).build())
                .execute().use { r -> if (!r.isSuccessful) throw HttpStatusException(r.code); r.body!!.bytes() }
        }
        if (!looksLikeTorrent(bytes)) throw PermanentException("Not a valid .torrent file")
        val info = try { TorrentInfo(bytes) } catch (e: Throwable) {
            if (e is UnsatisfiedLinkError || e is NoClassDefFoundError) throw IOException(UNSUPPORTED_CPU)
            throw PermanentException("Not a valid .torrent file")
        }
        cached.writeBytes(bytes)
        return info
    }

    data class TFile(val index: Int, val path: String, val size: Long)

    /** Lists the files inside a torrent / magnet (fetches the metadata once, then it is cached). */
    suspend fun files(item: DownloadItem, client: OkHttpClient): List<TFile> = withContext(Dispatchers.IO) {
        val dir = File(workDir, item.id).apply { mkdirs() }
        val fs = info(item, dir, client).files()
        (0 until fs.numFiles()).map { TFile(it, fs.filePath(it), fs.fileSize(it)) }
    }

    /** Runs until the torrent is complete. Returns the item (status DONE) and the downloaded file/folder. */
    suspend fun download(item: DownloadItem, client: OkHttpClient, onProgress: suspend (DownloadItem) -> Unit): Pair<DownloadItem, File> = withContext(Dispatchers.IO) {
        val s = session()
        val dir = File(workDir, item.id).apply { mkdirs() }
        val ti = info(item, dir, client)
        val name = SegmentedDownloader.sanitize(ti.name())
        var cur = item.copy(fileName = name, totalBytes = ti.totalSize(), mimeType = if (ti.numFiles() == 1) FileNamer.extToMime[FileNamer.ext(name)] ?: item.mimeType else item.mimeType)
        onProgress(cur)
        val pick = item.torrentFiles?.toSet()
        val prios = if (pick == null) null else Array(ti.numFiles()) { if (it in pick) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE }
        if (prios != null) s.download(ti, dir, null, prios, null, org.libtorrent4j.swig.torrent_flags_t()) else s.download(ti, dir)
        val th = s.find(ti.infoHash()) ?: throw IOException("The torrent could not be started")
        if (prios != null) th.prioritizeFiles(prios)
        th.resume()
        try {
            while (true) {
                coroutineContext.ensureActive()
                s.downloadRateLimit(limiter.bytesPerSecond.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let { if (item.speedLimitBytes > 0) minOf(it.takeIf { v -> v > 0 } ?: Int.MAX_VALUE, item.speedLimitBytes.toInt()) else it })
                val st = th.status()
                val wanted = st.totalWanted().takeIf { it > 0 } ?: ti.totalSize()
                cur = cur.copy(totalBytes = wanted, downloadedBytes = st.totalWantedDone(), torrentPeers = st.numPeers())
                onProgress(cur)
                if (st.isFinished || st.isSeeding || (wanted > 0 && st.totalWantedDone() >= wanted)) break
                delay(1000)
            }
        } finally {
            runCatching { s.remove(th) }
        }
        val out = File(dir, ti.name())
        if (pick != null) {
            // Leave only the picked files (libtorrent may keep a hidden part-file for the rest).
            val fs = ti.files()
            for (i in 0 until fs.numFiles()) if (i !in pick) File(dir, fs.filePath(i)).delete()
            dir.listFiles()?.filter { it.name.startsWith(".") && it.name.endsWith(".parts") }?.forEach { it.delete() }
        }
        cur.copy(status = Status.DONE, downloadedBytes = cur.totalBytes, finishedAt = System.currentTimeMillis()).also { onProgress(it) } to out
    }

    /**
     * Seeds a finished torrent from its work folder until [ratio] (uploaded / downloaded, 0 = no limit)
     * or [maxMinutes] (0 = no limit) is reached, or the coroutine is cancelled (user pressed Stop).
     */
    suspend fun seed(item: DownloadItem, client: OkHttpClient, ratio: Float, maxMinutes: Int, uploadLimit: Int, onProgress: suspend (uploaded: Long, peers: Int) -> Unit) = withContext(Dispatchers.IO) {
        val s = session()
        val dir = File(workDir, item.id)
        val ti = info(item, dir, client)
        val pick = item.torrentFiles?.toSet()
        val prios = if (pick == null) null else Array(ti.numFiles()) { if (it in pick) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE }
        if (prios != null) s.download(ti, dir, null, prios, null, org.libtorrent4j.swig.torrent_flags_t()) else s.download(ti, dir)
        val th = s.find(ti.infoHash()) ?: return@withContext
        th.resume()
        val t0 = System.currentTimeMillis()
        try {
            while (true) {
                coroutineContext.ensureActive()
                s.uploadRateLimit(uploadLimit.coerceAtLeast(0))
                val st = th.status()
                val up = st.allTimeUpload()
                onProgress(up, st.numPeers())
                if (shouldStopSeeding(up, item.totalBytes, ratio, maxMinutes, System.currentTimeMillis() - t0)) break
                delay(2000)
            }
        } finally { runCatching { s.remove(th) } }
    }

    companion object {
        const val UNSUPPORTED_CPU = "Torrents not supported on this CPU"
        private val BTIH = Regex("(?i)[?&]xt=urn:(btih:([0-9a-f]{40}|[a-z2-7]{32})|btmh:1220[0-9a-f]{64})(&|$)")

        /** A magnet link must name the torrent (xt=urn:btih / btmh), otherwise no peer can answer. */
        fun isValidMagnet(url: String): Boolean = url.startsWith("magnet:?", true) && BTIH.containsMatchIn(url)

        /** A .torrent file is a bencoded dictionary with an "info" key. */
        fun looksLikeTorrent(b: ByteArray): Boolean =
            b.size in 40..(64 * 1024 * 1024) && b[0] == 'd'.code.toByte() && String(b, 0, minOf(b.size, 64 * 1024), Charsets.ISO_8859_1).contains("4:info")
        fun shouldStopSeeding(uploaded: Long, downloaded: Long, ratio: Float, maxMinutes: Int, elapsedMs: Long): Boolean {
            if (ratio > 0f && downloaded > 0 && uploaded.toDouble() / downloaded >= ratio) return true
            if (maxMinutes > 0 && elapsedMs >= maxMinutes * 60_000L) return true
            return false
        }
    }

    fun workFolder(id: String) = File(workDir, id)
}
