package io.github.kyotobyte.cdmplus.data

import io.github.kyotobyte.cdmplus.engine.DownloadItem
import io.github.kyotobyte.cdmplus.ui.extractUrls
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupEntry(val url: String, val fileName: String? = null, val status: String? = null, val size: Long = -1)

/** Export/import of the download list. Import accepts our JSON or any text with links. */
object ListBackup {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    fun export(items: List<DownloadItem>): String =
        json.encodeToString(items.map { BackupEntry(it.url, it.fileName, it.status.name, it.totalBytes) })

    fun parse(text: String): List<BackupEntry> =
        runCatching { json.decodeFromString<List<BackupEntry>>(text) }.getOrNull()
            ?: extractUrls(text).map { BackupEntry(it) }
}
