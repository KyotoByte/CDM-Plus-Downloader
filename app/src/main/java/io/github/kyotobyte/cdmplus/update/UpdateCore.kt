package io.github.kyotobyte.cdmplus.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

/** update.json at the root of the GitHub repo (main branch). */
@Serializable
data class UpdateInfo(
    val versionCode: Long = 0,
    val versionName: String = "",
    val notes: String = "",
    val apks: Map<String, String> = emptyMap(),
)

/** notice.json at the root of the GitHub repo. An empty id means "no announcement". */
@Serializable
data class Notice(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val url: String? = null,
)

sealed interface CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult
    data object Latest : CheckResult
    data object Failed : CheckResult
}

/**
 * Plain-JVM logic for update checks and announcements: no server, no tracking.
 * The app only reads two public files from GitHub; nothing is sent about the user.
 */
object UpdateCore {
    const val RAW_BASE = "https://raw.githubusercontent.com/KyotoByte/CDM-Plus-Downloader/main/"
    const val UPDATE_URL = RAW_BASE + "update.json"
    const val NOTICE_URL = RAW_BASE + "notice.json"
    const val MAX_JSON_BYTES = 64 * 1024L
    const val MAX_APK_BYTES = 300L * 1024 * 1024
    /** Checks on app open run at most this often (the daily background check runs regardless). */
    const val OPEN_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parseUpdate(text: String?): UpdateInfo? {
        if (text.isNullOrBlank()) return null
        val info = runCatching { json.decodeFromString(UpdateInfo.serializer(), text) }.getOrNull() ?: return null
        return info.takeIf { it.versionCode > 0 }
    }

    fun parseNotice(text: String?): Notice? {
        if (text.isNullOrBlank()) return null
        val n = runCatching { json.decodeFromString(Notice.serializer(), text) }.getOrNull() ?: return null
        val clean = n.copy(
            id = n.id.trim(),
            title = n.title.trim(),
            message = n.message.trim(),
            url = n.url?.trim()?.takeIf { isWebUrl(it) },
        )
        return clean.takeIf { it.id.isNotEmpty() && (it.title.isNotEmpty() || it.message.isNotEmpty()) }
    }

    fun evaluate(info: UpdateInfo?, installedVersionCode: Long): CheckResult = when {
        info == null -> CheckResult.Failed
        info.versionCode > installedVersionCode -> CheckResult.Available(info)
        else -> CheckResult.Latest
    }

    /** APK for the phone's primary CPU type, else the universal one. Only https links are used. */
    fun pickApk(info: UpdateInfo, supportedAbis: List<String>): String? {
        val primary = supportedAbis.firstOrNull()?.let { info.apks[it] }
        return listOfNotNull(primary, info.apks["universal"]).firstOrNull { isHttpsUrl(it) }
    }

    fun isHttpsUrl(u: String): Boolean = u.startsWith("https://", ignoreCase = true) && u.toHttpUrlOrNull() != null
    fun isWebUrl(u: String): Boolean = (u.startsWith("https://", true) || u.startsWith("http://", true)) && u.toHttpUrlOrNull() != null

    /** What the user sees for an update: a system notification (once per version) and a banner (until updated or dismissed for that version). */
    data class UpdateUi(val notify: Boolean, val banner: Boolean)

    fun updateUi(info: UpdateInfo?, installedVersionCode: Long, enabled: Boolean, lastNotifiedVersionCode: Long, dismissedVersionCode: Long): UpdateUi {
        if (info == null || !enabled || info.versionCode <= installedVersionCode) return UpdateUi(notify = false, banner = false)
        return UpdateUi(notify = info.versionCode > lastNotifiedVersionCode, banner = info.versionCode > dismissedVersionCode)
    }

    /** "v1.1" from update.json's versionName (falls back to the build number). */
    fun label(info: UpdateInfo): String {
        val n = info.versionName.trim()
        return when {
            n.isEmpty() -> "build ${info.versionCode}"
            n.startsWith("v", ignoreCase = true) -> n
            else -> "v$n"
        }
    }

    fun notificationTitle(info: UpdateInfo) = "New update available: ${label(info)}"

    fun shouldCheckOnOpen(lastCheck: Long, now: Long): Boolean = lastCheck <= 0 || now < lastCheck || now - lastCheck >= OPEN_CHECK_INTERVAL_MS
    fun shouldNotifyUpdate(versionCode: Long, lastNotifiedVersionCode: Long): Boolean = versionCode > lastNotifiedVersionCode
    fun shouldNotifyNotice(id: String, lastSeenId: String?): Boolean = id.isNotEmpty() && id != lastSeenId

    /** Same signer as the installed app: a signer of the new APK (or its rotation history) is one of ours. */
    fun signersMatch(installed: Set<String>, archive: Set<String>): Boolean =
        installed.isNotEmpty() && archive.isNotEmpty() && archive.any { it in installed }

    /** Returns null when the downloaded APK is safe to hand to the installer, else the reason it was refused. */
    fun verifyApk(
        expectedPackage: String, installedVersionCode: Long, installedSigners: Set<String>,
        apkPackage: String?, apkVersionCode: Long, apkSigners: Set<String>,
    ): String? = when {
        apkPackage == null -> "The downloaded file isn't a valid APK. Nothing was installed."
        apkPackage != expectedPackage -> "This APK is a different app ($apkPackage). Refused for your safety."
        !signersMatch(installedSigners, apkSigners) -> "Signature check failed: this APK isn't signed by the CDM+ developer. Refused for your safety."
        apkVersionCode <= installedVersionCode -> "This APK isn't newer than the version you have."
        else -> null
    }

    fun client(base: OkHttpClient? = null): OkHttpClient = (base?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Small JSON file over HTTP. Any failure (offline, 404, timeout, too big) is just null. */
    fun fetchText(client: OkHttpClient, url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").header("Accept", "application/json, text/plain, */*").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val body = r.body ?: return@use null
            if (body.contentLength() > MAX_JSON_BYTES) return@use null
            val src = body.source()
            val buf = Buffer()
            while (true) {
                val n = src.read(buf, 8192)
                if (n == -1L) break
                if (buf.size > MAX_JSON_BYTES) return@use null
            }
            buf.readUtf8()
        }
    }.getOrNull()
}
