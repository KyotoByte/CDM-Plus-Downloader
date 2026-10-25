package io.github.kyotobyte.cdmplus.engine

import kotlinx.serialization.Serializable

@Serializable
enum class Status { QUEUED, SCHEDULED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

/** One byte range of a file. [end] is inclusive; -1 means "until the server stops". */
@Serializable
data class Segment(val index: Int, val start: Long, val end: Long, val done: Long = 0) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val isComplete: Boolean get() = end >= 0 && done >= length
}

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val threads: Int = 8,
    val segments: List<Segment> = emptyList(),
    val supportsRange: Boolean = false,
    val mimeType: String? = null,
    val etag: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val tempPath: String? = null,
    val savedUri: String? = null,
    val savedPath: String? = null,
    val error: String? = null,
    val networkError: Boolean = false,
    val waitingReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val scheduledAt: Long? = null,
    val retries: Int = 0,
    /** Per-file speed limit in bytes/second (0 = none). */
    val speedLimitBytes: Long = 0,
    /** HLS (m3u8) downloads: media segments total / finished. */
    val hlsTotal: Int = 0,
    val hlsDone: Int = 0,
    /** Torrents: connected peers. */
    val torrentPeers: Int = 0,
    /** Torrents: indices of the files to download inside the torrent (null = all). */
    val torrentFiles: List<Int>? = null,
    /** Torrents: seeding after the download (bytes uploaded so far). */
    val seeding: Boolean = false,
    val uploadedBytes: Long = 0,
    /** Second stream merged with the first after download (e.g. YouTube video + audio). */
    val audioUrl: String? = null,
    /** "merge" (video+audio), "extract_audio" (keep only the music track). */
    val postProcess: String? = null,
    /** Where the item came from (Instagram / TikTok / YouTube...), shown in the list. */
    val source: String? = null,
    /** Folder inside Downloads/CDM+ to save into, e.g. "My MEGA folder/Sub" (shared-folder downloads). */
    val subDir: String? = null,
) {
    val isTorrent: Boolean get() = url.startsWith("magnet:", true) || url.substringBefore('?').endsWith(".torrent", true) || mimeType == "application/x-bittorrent"
    val isHls: Boolean get() = hlsTotal > 0 || url.substringBefore('?').endsWith(".m3u8", true) || mimeType?.contains("mpegurl", true) == true
    val progress: Float
        get() = if (hlsTotal > 0) hlsDone.toFloat() / hlsTotal else if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = status == Status.RUNNING || status == Status.QUEUED
}

enum class Category(val label: String) { ALL("All types"), VIDEO("Video"), MUSIC("Music"), IMAGES("Images"), DOCS("Docs"), APPS("Apps"), ARCHIVES("Archives"), OTHER("Other") }

fun categoryOf(fileName: String, mime: String? = null): Category {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val m = mime?.lowercase() ?: ""
    return when {
        m.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v", "ts") -> Category.VIDEO
        m.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma") -> Category.MUSIC
        m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic") -> Category.IMAGES
        ext in setOf("apk", "xapk", "apks", "aab", "exe", "msi", "dmg", "deb") || m == "application/vnd.android.package-archive" -> Category.APPS
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "iso") -> Category.ARCHIVES
        ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "odt", "rtf", "md") || m.startsWith("text/") -> Category.DOCS
        else -> Category.OTHER
    }
}
