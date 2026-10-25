package io.github.kyotobyte.cdmplus.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Persists the download list as JSON (atomic write) so downloads survive restarts. */
class DownloadStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): List<DownloadItem> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<DownloadItem>>(file.readText())
    }.getOrElse { emptyList() }

    @Synchronized
    fun save(items: List<DownloadItem>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(items))
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }
}
