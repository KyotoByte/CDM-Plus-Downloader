package io.github.kyotobyte.cdmplus.engine

/**
 * Picks a correct file name + extension. Order: Content-Disposition, a name that already has a
 * real extension, the final URL path (after redirects, query removed), the Content-Type, and
 * finally the file's first bytes (magic numbers) once it's downloaded.
 */
object FileNamer {
    private val weakExt = setOf("", "bin", "tmp", "dat", "file", "download", "octet-stream", "php", "asp", "aspx", "jsp", "cgi", "do", "action", "unknown")
    private val unknownMime = setOf("application/octet-stream", "binary/octet-stream", "application/binary", "application/force-download", "application/x-download", "application/download", "application/unknown", "")

    val mimeToExt = mapOf(
        "video/mp4" to "mp4", "video/x-matroska" to "mkv", "video/webm" to "webm", "video/quicktime" to "mov", "video/3gpp" to "3gp",
        "video/x-msvideo" to "avi", "video/mp2t" to "ts", "video/x-flv" to "flv", "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/flac" to "flac", "audio/wav" to "wav",
        "audio/x-wav" to "wav", "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/svg+xml" to "svg",
        "application/pdf" to "pdf", "application/zip" to "zip", "application/x-zip-compressed" to "zip", "application/vnd.android.package-archive" to "apk",
        "application/x-rar-compressed" to "rar", "application/vnd.rar" to "rar", "application/x-7z-compressed" to "7z", "application/gzip" to "gz",
        "application/x-tar" to "tar", "application/epub+zip" to "epub", "application/x-bittorrent" to "torrent", "text/plain" to "txt",
        "text/csv" to "csv", "application/json" to "json", "application/msword" to "doc", "application/x-iso9660-image" to "iso",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.apple.mpegurl" to "ts", "application/x-mpegurl" to "ts",
    )
    val extToMime: Map<String, String> = mimeToExt.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() }

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: ""
    fun base(name: String): String = if (ext(name).isEmpty()) name else name.substringBeforeLast('.')
    fun isWeak(name: String) = ext(name) in weakExt

    fun extFromMime(mime: String?): String? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (m in unknownMime) return null
        return mimeToExt[m]
    }

    /** Applies rules a-d. [current] is the name we have; [disposition] the server/browser header. */
    fun improve(current: String, disposition: String?, finalUrl: String?, mime: String?): String {
        SegmentedDownloader.fileNameFromDisposition(disposition)?.let { d ->
            val clean = SegmentedDownloader.sanitize(d)
            if (!isWeak(clean)) return clean
            extFromMime(mime)?.let { return base(clean) + "." + it }
        }
        if (!isWeak(current)) return current
        if (finalUrl != null) {
            val urlName = SegmentedDownloader.fileNameFromUrl(finalUrl)
            val e = ext(urlName)
            if (e !in weakExt) return if (current.isBlank() || base(current).lowercase() in genericBases) urlName else base(current) + "." + e
        }
        extFromMime(mime)?.let { return base(current).ifBlank { "download" } + "." + it }
        return current
    }

    private val genericBases = setOf("download", "downloadfile", "file", "index", "get", "video", "media", "stream", "dl", "")

    /** Rule e: magic numbers. */
    fun sniff(h: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = h.size >= i + b.size && b.indices.all { (h[i + it].toInt() and 0xff) == b[it] }
        fun ascii(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it].toInt().toChar() == s[it] }
        return when {
            ascii(4, "ftyp") -> when {
                ascii(8, "M4A") -> "m4a"; ascii(8, "qt") -> "mov"; ascii(8, "3g") -> "3gp"; ascii(8, "heic") -> "heic"; else -> "mp4"
            }
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) -> "mp3"
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9) -> "aac"
            ascii(0, "%PDF") -> "pdf"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            ascii(0, "GIF8") -> "gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "avi"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (String(h.copyOf(minOf(h.size, 64)), Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            ascii(0, "OggS") -> "ogg"
            ascii(0, "fLaC") -> "flac"
            ascii(0, "Rar!") -> "rar"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            at(0, 0x1F, 0x8B) -> "gz"
            ascii(0, "PK") && at(2, 0x03, 0x04) -> "zip"
            at(0, 0x47) && h.size > 188 && (h[188].toInt() and 0xff) == 0x47 -> "ts"
            ascii(0, "FLV") -> "flv"
            else -> null
        }
    }

    /** After download: if the name is still weak, use the sniffed type. */
    fun afterDownload(name: String, head: ByteArray): String {
        if (!isWeak(name)) return name
        val e = sniff(head) ?: return name
        return base(name).ifBlank { "download" } + "." + e
    }
}
