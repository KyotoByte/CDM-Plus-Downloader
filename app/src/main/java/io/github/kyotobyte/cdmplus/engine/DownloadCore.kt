package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

data class EngineSettings(
    val threads: Int = 8,
    val maxSimultaneous: Int = 3,
    val speedLimitBytes: Long = 0,
    val autoResume: Boolean = true,
    val userAgent: String = "",
    /** Automatic retries after a network error (0 = unlimited). */
    val retryCount: Int = 5,
    val retryDelaySec: Int = 5,
    /** Max parts per domain, e.g. "example.com" to 2 (servers that ban many connections). */
    val domainParts: Map<String, Int> = emptyMap(),
    /** Torrents: keep seeding after finishing, until this ratio (0 = forever) or minutes (0 = no limit). */
    val seedEnabled: Boolean = false,
    val seedRatio: Float = 1.0f,
    val seedMinutes: Int = 0,
    val uploadLimitBytes: Int = 0,
)

/** What the queue needs from the platform (Android or a test). */
interface DownloadPlatform {
    fun partialDir(): File
    /** Moves a finished temp file to its public home; returns (uri, path). */
    suspend fun publish(item: DownloadItem, file: File): Pair<String?, String?>
    /** null = network usable; otherwise the reason we are waiting (e.g. "Waiting for Wi-Fi"). */
    fun networkBlockReason(): String? = null
    fun onActiveChanged(active: Boolean) {}
    fun onFinished(item: DownloadItem) {}
    fun schedule(item: DownloadItem) {}
    fun deletePublished(item: DownloadItem) {}
    /** Torrents can be a whole folder: publish every file. Default: leave it where it is. */
    suspend fun publishTree(item: DownloadItem, root: File): Pair<String?, String?> = if (root.isFile) publish(item, root) else null to root.path
    /** Joins a video-only and an audio-only file into [out] without re-encoding. False if not possible here. */
    fun mux(video: File, audio: File, out: File): Boolean = runCatching { Mp4Joiner.join(video, audio, out); true }.getOrElse { out.delete(); false }
    /** Copies just the audio track of [input] into [out] (no re-encode). Returns the file extension used, or null. */
    fun extractAudio(input: File, out: File): String? = null
}

/**
 * The download queue: add / pause / resume / retry / cancel, max-simultaneous,
 * scheduling, auto-resume when the network comes back, persistence.
 */
@OptIn(FlowPreview::class)
class DownloadCore(
    private val scope: CoroutineScope,
    private val store: DownloadStore,
    client: OkHttpClient,
    private val platform: DownloadPlatform,
    private val settings: () -> EngineSettings,
) {
    val limiter = SpeedLimiter()
    @Volatile private var downloader = SegmentedDownloader(client, limiter)
    @Volatile private var hls = HlsDownloader(client, limiter)
    private val ftp = FtpDownloader(limiter)
    val torrents by lazy { TorrentDownloader(File(platform.partialDir(), "torrents"), limiter) }
    @Volatile private var httpClient = client
    private val _history = MutableStateFlow<List<Long>>(List(60) { 0L })
    /** Total speed over the last 60 seconds (for the live graph). */
    val speedHistory: StateFlow<List<Long>> = _history.asStateFlow()

    /** Swap the HTTP client (proxy settings changed). Running downloads keep their old one. */
    val client: OkHttpClient get() = httpClient
    fun setClient(c: OkHttpClient) { httpClient = c; downloader = SegmentedDownloader(c, limiter); hls = HlsDownloader(c, limiter) }
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val _speeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Live bytes/second per running download. */
    val speeds: StateFlow<Map<String, Long>> = _speeds.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastSample = ConcurrentHashMap<String, Pair<Long, Long>>()

    init {
        // Anything that was running when the process died is re-queued (resume after restart).
        val restored = store.load().map {
            if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED) else it
        }
        _items.value = restored
        // Save at most every 1.5 s, also WHILE downloads run (debounce never fired during a busy download,
        // so a download killed with the app could be lost from the list).
        scope.launch { _items.sample(1500).collect { runCatching { store.save(it) } } }
        restored.filter { it.status == Status.SCHEDULED }.forEach { platform.schedule(it) }
        scope.launch {
            while (true) {
                delay(1000)
                val total = if (_items.value.any { it.status == Status.RUNNING }) _speeds.value.values.sum() else 0L
                _history.update { (it + total).takeLast(60) }
            }
        }
    }

    fun start() = pump()

    fun add(
        url: String,
        fileName: String? = null,
        threads: Int? = null,
        headers: Map<String, String> = emptyMap(),
        scheduledAt: Long? = null,
        mime: String? = null,
        speedLimitBytes: Long = 0,
        torrentFiles: List<Int>? = null,
        audioUrl: String? = null,
        postProcess: String? = null,
        source: String? = null,
        id0: String? = null,
        subDir: String? = null,
    ): String {
        val id = id0 ?: UUID.randomUUID().toString()
        var name = SegmentedDownloader.sanitize(fileName?.trim().takeUnless { it.isNullOrBlank() } ?: SegmentedDownloader.fileNameFromUrl(url))
        if (name.endsWith(".m3u8", true) || name.endsWith(".m3u", true)) name = name.substringBeforeLast('.') + ".mp4" 
        if (url.startsWith("magnet:", true)) {
            name = SegmentedDownloader.sanitize(Regex("[?&]dn=([^&]+)").find(url)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "Torrent")
        }
        val future = scheduledAt != null && scheduledAt > System.currentTimeMillis()
        val item = DownloadItem(
            id = id, url = url.trim(), fileName = name,
            threads = domainCap(url, (threads ?: settings().threads).coerceIn(1, 32)),
            speedLimitBytes = speedLimitBytes,
            headers = headers, mimeType = mime,
            torrentFiles = torrentFiles, audioUrl = audioUrl, postProcess = postProcess, source = source,
            subDir = SegmentedDownloader.sanitizeDir(subDir),
            status = if (future) Status.SCHEDULED else Status.QUEUED,
            scheduledAt = if (future) scheduledAt else null,
            tempPath = File(platform.partialDir(), "$id.part").absolutePath,
        )
        _items.update { listOf(item) + it }
        runCatching { store.save(_items.value) } // a new download survives the app being killed right away
        if (future) platform.schedule(item) else pump()
        return id
    }

    /** Applies the per-domain part limit from settings. */
    fun domainCap(url: String, threads: Int): Int {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val caps = settings().domainParts
        var h = host
        while (true) {
            caps[h]?.let { return threads.coerceAtMost(it.coerceAtLeast(1)) }
            val dot = h.indexOf('.')
            if (dot < 0) return threads
            h = h.substring(dot + 1)
        }
    }

    /** Point a failed/expired download at a fresh link, keeping what was already downloaded. */
    private val seedJobs = ConcurrentHashMap<String, Job>()

    /** Keeps a finished torrent seeding (setting), until the ratio/time limit or Stop seeding. */
    fun startSeeding(item: DownloadItem) {
        if (seedJobs.containsKey(item.id)) return
        val s = settings()
        mutate(item.id) { it.copy(seeding = true) }
        seedJobs[item.id] = scope.launch {
            try {
                torrents.seed(item, httpClient, s.seedRatio, s.seedMinutes, s.uploadLimitBytes) { up, peers -> mutate(item.id) { it.copy(uploadedBytes = up, torrentPeers = peers) } }
            } catch (_: Throwable) {
            } finally {
                seedJobs.remove(item.id)
                mutate(item.id) { it.copy(seeding = false) }
                torrents.workFolder(item.id).deleteRecursively()
                store.save(_items.value)
            }
        }
    }

    fun stopSeeding(id: String) { seedJobs[id]?.cancel() }

    fun updateLink(id: String, newUrl: String) {
        jobs.remove(id)?.cancel(CancellationException("relink"))
        mutate(id) { it.copy(url = newUrl.trim(), status = Status.QUEUED, error = null, networkError = false, retries = 0) }
        store.save(_items.value)
        pump()
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel(CancellationException("paused"))
        mutate(id) { if (it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.SCHEDULED) it.copy(status = Status.PAUSED, waitingReason = null) else it }
        pump()
    }

    fun resume(id: String) {
        mutate(id) { if (it.status in setOf(Status.PAUSED, Status.FAILED, Status.SCHEDULED, Status.CANCELED)) it.copy(status = Status.QUEUED, error = null, networkError = false, scheduledAt = null) else it }
        pump()
    }

    fun retry(id: String) {
        mutate(id) { it.copy(status = Status.QUEUED, error = null, networkError = false, retries = it.retries + 1) }
        pump()
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel(CancellationException("canceled"))
        val item = find(id) ?: return
        item.tempPath?.let { File(it).delete(); File("$it.hls").deleteRecursively() }
        mutate(id) { it.copy(status = Status.CANCELED, segments = emptyList(), downloadedBytes = 0, waitingReason = null) }
        pump()
    }

    fun delete(id: String, deleteFile: Boolean) {
        jobs.remove(id)?.cancel(CancellationException("deleted"))
        val item = find(id) ?: return
        item.tempPath?.let { File(it).delete(); File("$it.hls").deleteRecursively() }
        if (deleteFile && item.status == Status.DONE) platform.deletePublished(item)
        _items.update { list -> list.filterNot { it.id == id } }
        pump()
    }

    fun pauseAll() = items.value.filter { it.isActive }.forEach { pause(it.id) }
    fun resumeAll() = items.value.filter { it.status == Status.PAUSED || (it.status == Status.FAILED && it.networkError) }.forEach { resume(it.id) }

    /** Called by the scheduler alarm. */
    fun releaseDue(now: Long = System.currentTimeMillis()) {
        _items.update { list -> list.map { if (it.status == Status.SCHEDULED && (it.scheduledAt ?: 0) <= now) it.copy(status = Status.QUEUED, scheduledAt = null) else it } }
        pump()
    }

    /** Called when connectivity changes. */
    fun onNetworkChanged() {
        val block = platform.networkBlockReason()
        if (block != null) {
            // Stop running work and wait; it re-queues itself once allowed.
            items.value.filter { it.status == Status.RUNNING }.forEach { item ->
                jobs.remove(item.id)?.cancel(CancellationException("network"))
                mutate(item.id) { it.copy(status = Status.QUEUED, waitingReason = block) }
            }
        } else if (settings().autoResume) {
            _items.update { list -> list.map { if (it.status == Status.FAILED && it.networkError) it.copy(status = Status.QUEUED, error = null, networkError = false) else it } }
        }
        pump()
    }

    fun setSpeedLimit(bytesPerSecond: Long) { limiter.bytesPerSecond = bytesPerSecond }

    @Synchronized
    private fun pump() {
        limiter.bytesPerSecond = settings().speedLimitBytes
        val block = platform.networkBlockReason()
        val list = _items.value
        if (block != null) {
            _items.update { l -> l.map { if (it.status == Status.QUEUED) it.copy(waitingReason = block) else it } }
        } else {
            val running = list.count { it.status == Status.RUNNING }
            val free = settings().maxSimultaneous.coerceIn(1, 30) - running
            // Oldest queued first.
            list.filter { it.status == Status.QUEUED }.sortedBy { it.createdAt }.take(free.coerceAtLeast(0)).forEach { launchJob(it) }
        }
        platform.onActiveChanged(_items.value.any { it.status == Status.RUNNING || (it.status == Status.QUEUED && block == null) })
    }

    private fun launchJob(item: DownloadItem) {
        mutate(item.id) { it.copy(status = Status.RUNNING, error = null, waitingReason = null) }
        lastSample[item.id] = System.nanoTime() to item.downloadedBytes
        val job = scope.launch {
            val dest = File(item.tempPath ?: File(platform.partialDir(), "${item.id}.part").absolutePath)
            try {
                val found = find(item.id) ?: return@launch
                val ua = settings().userAgent
                val running = if (ua.isNotBlank() && found.headers.keys.none { it.equals("User-Agent", true) }) found.copy(headers = found.headers + ("User-Agent" to ua)) else found
                val onSnap: suspend (DownloadItem) -> Unit = { snap ->
                    updateSpeed(item.id, snap.downloadedBytes)
                    mutate(item.id) { cur -> if (cur.status == Status.RUNNING) snap.copy(status = Status.RUNNING) else cur.copy(segments = snap.segments, downloadedBytes = snap.downloadedBytes, totalBytes = snap.totalBytes, supportsRange = snap.supportsRange, etag = snap.etag, mimeType = snap.mimeType, hlsTotal = snap.hlsTotal, hlsDone = snap.hlsDone) }
                }
                if (running.isTorrent) {
                    val (fin, root) = torrents.download(running, httpClient, onSnap)
                    val (uri, path) = platform.publishTree(fin, root)
                    val done = fin.copy(savedUri = uri, savedPath = path, segments = emptyList(), tempPath = null)
                    mutate(item.id) { done }
                    store.save(_items.value)
                    platform.onFinished(done)
                    val st = settings()
                    if (st.seedEnabled) startSeeding(done) else torrents.workFolder(item.id).deleteRecursively()
                    return@launch
                }
                // Separate audio stream first (YouTube high quality = video-only + audio-only streams).
                val audioFile = File(dest.path + ".audio")
                if (running.audioUrl != null && !File(dest.path + ".audio.done").exists()) {
                    mutate(item.id) { it.copy(waitingReason = "Fetching audio track") }
                    downloader.download(running.copy(id = item.id + "-a", url = running.audioUrl, segments = emptyList(), totalBytes = -1, downloadedBytes = 0, threads = 4), audioFile) {}
                    File(dest.path + ".audio.done").writeText("1")
                    mutate(item.id) { it.copy(waitingReason = null) }
                }
                var finished = when {
                    running.url.startsWith("ftp://", true) -> ftp.download(running, dest, onSnap)
                    running.isHls -> hls.download(running, dest, running.threads.coerceAtMost(16), onSnap)
                    else -> downloader.download(running, dest, onSnap)
                }
                MegaCrypto.keyFromTag(running.postProcess)?.let { key ->
                    // MEGA files arrive encrypted (AES-128-CTR): decrypt in place before saving.
                    mutate(item.id) { it.copy(waitingReason = "Decrypting (MEGA)") }
                    MegaCrypto.decryptInPlace(dest, key)
                    mutate(item.id) { it.copy(waitingReason = null) }
                }
                if (running.audioUrl != null) {
                    val merged = File(dest.path + ".mux")
                    val ok = runCatching { platform.mux(dest, audioFile, merged) }.getOrDefault(false)
                    if (ok) { dest.delete(); merged.renameTo(dest) } else {
                        merged.delete()
                        // Never lose the sound: save the audio track as its own .m4a next to the video.
                        val audioName = finished.fileName.substringBeforeLast('.') + " (sound).m4a"
                        val saved = runCatching { platform.publish(finished.copy(id = item.id + "-a", fileName = audioName, mimeType = "audio/mp4"), audioFile) }.isSuccess
                        finished = finished.copy(error = if (saved) "Couldn't join video + sound on this phone, so they were saved as 2 files: the video and \"$audioName\"" else "Saved the video, but joining the sound failed and the sound file couldn't be saved")
                    }
                    audioFile.delete(); File(dest.path + ".audio.done").delete()
                    finished = finished.copy(totalBytes = dest.length(), downloadedBytes = dest.length())
                }
                if (running.postProcess == "extract_audio") {
                    val a = File(dest.path + ".m4a")
                    val ext = runCatching { platform.extractAudio(dest, a) }.getOrNull()
                    if (ext != null) {
                        dest.delete(); a.renameTo(dest)
                        finished = finished.copy(fileName = finished.fileName.substringBeforeLast('.') + "." + ext, mimeType = if (ext == "m4a") "audio/mp4" else "audio/webm", totalBytes = dest.length(), downloadedBytes = dest.length())
                    } else { a.delete(); finished = finished.copy(error = "This video has no separate music track to extract; kept the video") }
                }
                // Rule e: still no real extension (e.g. ".bin")? Look at the first bytes.
                val named = if (FileNamer.isWeak(finished.fileName)) {
                    val head = runCatching { dest.inputStream().use { s -> ByteArray(256).let { b -> val n = s.read(b); b.copyOf(maxOf(n, 0)) } } }.getOrDefault(ByteArray(0))
                    val n = FileNamer.afterDownload(finished.fileName, head)
                    if (n != finished.fileName) finished.copy(fileName = n, mimeType = FileNamer.extToMime[FileNamer.ext(n)] ?: finished.mimeType) else finished
                } else finished
                val (uri, path) = platform.publish(named, dest)
                val done = named.copy(savedUri = uri, savedPath = path, segments = emptyList(), tempPath = null)
                mutate(item.id) { done }
                store.save(_items.value)
                platform.onFinished(done)
            } catch (e: CancellationException) {
                // paused / canceled / network wait: state already set by the caller
            } catch (e: Throwable) {
                val net = DownloadErrors.retryable(e)
                val cur = find(item.id) ?: item
                val s = settings()
                val retryLeft = net && s.autoResume && (s.retryCount == 0 || cur.retries < s.retryCount)
                val failed = cur.copy(status = Status.FAILED, error = friendly(e) + if (retryLeft) " - retrying in ${s.retryDelaySec} s" else "", networkError = net)
                mutate(item.id) { failed }
                store.save(_items.value)
                if (retryLeft) {
                    scope.launch {
                        delay(s.retryDelaySec.coerceAtLeast(1) * 1000L)
                        mutate(item.id) { if (it.status == Status.FAILED) it.copy(status = Status.QUEUED, retries = it.retries + 1, error = null) else it }
                        pump()
                    }
                } else platform.onFinished(failed)
            } finally {
                jobs.remove(item.id)
                _speeds.update { it - item.id }
                lastSample.remove(item.id)
                if (platform.networkBlockReason() == null || true) pump()
            }
        }
        jobs[item.id] = job
    }

    private fun updateSpeed(id: String, bytes: Long) {
        val now = System.nanoTime()
        val (t0, b0) = lastSample[id] ?: (now to bytes)
        val dt = now - t0
        if (dt >= 900_000_000L) {
            val bps = ((bytes - b0).coerceAtLeast(0) * 1_000_000_000L / dt)
            val prev = _speeds.value[id] ?: bps
            _speeds.update { it + (id to (prev * 3 + bps * 7) / 10) }
            lastSample[id] = now to bytes
        }
    }

    private fun friendly(e: Throwable): String = DownloadErrors.message(e)

    fun find(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun mutate(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** Waits until no download is running or queued (tests). */
    suspend fun awaitIdle(timeoutMs: Long = 120_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (_items.value.none { it.status == Status.RUNNING || it.status == Status.QUEUED }) return
            delay(100)
        }
        throw IllegalStateException("not idle")
    }
}
