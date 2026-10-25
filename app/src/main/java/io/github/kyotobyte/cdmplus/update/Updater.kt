package io.github.kyotobyte.cdmplus.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.MainActivity
import io.github.kyotobyte.cdmplus.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Update notifications + announcements, read from two public files on GitHub. */
object Updater {
    const val CH_UPDATES = "cdm_updates"
    const val CH_NEWS = "cdm_news"
    const val EXTRA_UPDATE = "cdm_show_update"
    const val EXTRA_NOTICE = "cdm_show_notice"
    private const val JOB_ID = 41_011
    private const val NOTIF_UPDATE = 41_012
    private const val NOTIF_NOTICE = 41_013

    sealed interface Dl {
        data object Idle : Dl
        data class Downloading(val progress: Float) : Dl
        data class Ready(val file: File) : Dl
        data class Error(val message: String) : Dl
    }

    val available = MutableStateFlow<UpdateInfo?>(null)
    /** Banner hidden for this versionCode (and older) because the user closed it. */
    val bannerDismissedVc = MutableStateFlow(0L)
    val notice = MutableStateFlow<Notice?>(null)
    val showDialog = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val checkingNow = MutableStateFlow(false)
    val download = MutableStateFlow<Dl>(Dl.Idle)
    private val checking = AtomicBoolean(false)
    private var dlJob: Job? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cdm_updates", Context.MODE_PRIVATE)
    private fun settings() = CdmApp.instance.settings.value

    /** The debug build installs beside the real app; it never offers updates (they're for the release app). */
    fun isDebugBuild(ctx: Context) = ctx.packageName.endsWith(".debug")

    @Suppress("DEPRECATION")
    fun installedVersionCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(Long.MAX_VALUE)

    /** App start: restore what the last check found (works offline), make channels, arm the daily check. */
    fun init(ctx: Context) {
        runCatching {
            ensureChannels(ctx)
            val p = prefs(ctx)
            val s = settings()
            bannerDismissedVc.value = p.getLong("banner_dismissed_vc", 0)
            if (s.announcements) {
                val n = UpdateCore.parseNotice(p.getString("notice_json", null))
                notice.value = n?.takeIf { it.id != p.getString("notice_dismissed", null) }
            }
            if (s.updateNotify && !isDebugBuild(ctx)) {
                val info = UpdateCore.parseUpdate(p.getString("update_json", null))
                available.value = info?.takeIf { it.versionCode > installedVersionCode(ctx) }
            }
            schedule(ctx)
        }
    }

    /** Called whenever settings change: daily job on/off, hide what was switched off. */
    fun onSettingsChanged(ctx: Context) {
        runCatching {
            val s = settings()
            if (!s.announcements) notice.value = null
            if (!s.updateNotify) available.value = null
            schedule(ctx)
        }
    }

    fun schedule(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val s = settings()
            if (isDebugBuild(ctx) && !s.announcements || (!s.updateNotify && !s.announcements)) { js.cancel(JOB_ID); return }
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    fun onAppOpen(ctx: Context, scope: CoroutineScope) {
        val last = prefs(ctx).getLong("last_check", 0)
        if (UpdateCore.shouldCheckOnOpen(last, System.currentTimeMillis())) scope.launch { check(ctx, manual = false) }
    }

    /** One check of update.json + notice.json. Never throws; offline just means "nothing new". */
    suspend fun check(ctx: Context, manual: Boolean): CheckResult = withContext(Dispatchers.IO) {
        if (!checking.compareAndSet(false, true)) return@withContext CheckResult.Failed
        checkingNow.value = true
        try {
            val s = settings()
            val p = prefs(ctx)
            val client = UpdateCore.client(CdmApp.instance.buildClient(s))
            p.edit().putLong("last_check", System.currentTimeMillis()).apply()
            var result: CheckResult = CheckResult.Latest
            if (isDebugBuild(ctx)) {
                if (manual) status.value = "This is the debug build. Updates come to the normal CDM+ app."
            } else if (manual || s.updateNotify) {
                val text = UpdateCore.fetchText(client, UpdateCore.UPDATE_URL)
                val info = UpdateCore.parseUpdate(text)
                result = UpdateCore.evaluate(info, installedVersionCode(ctx))
                if (info != null) p.edit().putString("update_json", text).apply()
                when (result) {
                    is CheckResult.Available -> {
                        available.value = result.info
                        val ui = UpdateCore.updateUi(result.info, installedVersionCode(ctx), s.updateNotify, p.getLong("notified_vc", 0), p.getLong("banner_dismissed_vc", 0))
                        if (!manual && ui.notify) {
                            if (notifyUpdate(ctx, result.info)) p.edit().putLong("notified_vc", result.info.versionCode).apply()
                        }
                    }
                    CheckResult.Latest -> available.value = null
                    CheckResult.Failed -> Unit
                }
                if (manual) status.value = when (result) {
                    is CheckResult.Available -> "Update found: ${UpdateCore.label(result.info)}"
                    CheckResult.Latest -> "You're on the latest version"
                    CheckResult.Failed -> "Couldn't check right now. Check your connection and try again."
                }
                if (manual && result is CheckResult.Available) showDialog.value = true
            }
            if (s.announcements) {
                val text = UpdateCore.fetchText(client, UpdateCore.NOTICE_URL)
                if (text != null) {
                    p.edit().putString("notice_json", text).apply()
                    val n = UpdateCore.parseNotice(text)
                    notice.value = n?.takeIf { it.id != p.getString("notice_dismissed", null) }
                    if (n != null && UpdateCore.shouldNotifyNotice(n.id, p.getString("notice_seen", null))) {
                        if (notifyNotice(ctx, n)) p.edit().putString("notice_seen", n.id).apply()
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (manual) status.value = "Couldn't check right now. Check your connection and try again."
            CheckResult.Failed
        } finally {
            checking.set(false)
            checkingNow.value = false
        }
    }

    fun dismissUpdateBanner(ctx: Context) {
        val info = available.value ?: return
        prefs(ctx).edit().putLong("banner_dismissed_vc", info.versionCode).apply()
        bannerDismissedVc.value = info.versionCode
    }

    fun dismissNotice(ctx: Context) {
        val n = notice.value ?: return
        prefs(ctx).edit().putString("notice_dismissed", n.id).apply()
        notice.value = null
    }

    // ---- notifications ----

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_NEWS, "Announcements", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String, extra: String): Boolean {
        if (!canNotify(ctx)) return false
        return runCatching {
            ensureChannels(ctx)
            val open = Intent(ctx, MainActivity::class.java).putExtra(extra, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(id, n)
            true
        }.getOrDefault(false)
    }

    private fun notifyUpdate(ctx: Context, info: UpdateInfo): Boolean =
        post(ctx, NOTIF_UPDATE, CH_UPDATES, UpdateCore.notificationTitle(info), info.notes.ifBlank { "Tap to download and install CDM+ ${UpdateCore.label(info)}." }, EXTRA_UPDATE)

    private fun notifyNotice(ctx: Context, n: Notice): Boolean =
        post(ctx, NOTIF_NOTICE, CH_NEWS, n.title.ifBlank { "CDM+" }, n.message.ifBlank { n.title }, EXTRA_NOTICE)

    // ---- download + verify + install ----

    fun startDownload(ctx: Context, scope: CoroutineScope) {
        val info = available.value ?: return
        if (download.value is Dl.Downloading) return
        dlJob?.cancel()
        dlJob = scope.launch(Dispatchers.IO) {
            download.value = Dl.Downloading(-1f)
            val url = UpdateCore.pickApk(info, Build.SUPPORTED_ABIS.toList())
            if (url == null) { download.value = Dl.Error("This update has no APK for your phone's CPU type."); return@launch }
            val dir = File(ctx.cacheDir, "updates")
            try {
                dir.deleteRecursively(); dir.mkdirs()
                val part = File(dir, "update.apk.part")
                val client = CdmApp.instance.buildClient(settings()).newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val body = r.body ?: throw java.io.IOException("empty")
                    val total = body.contentLength()
                    if (total > UpdateCore.MAX_APK_BYTES) throw java.io.IOException("too big")
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (done > UpdateCore.MAX_APK_BYTES) throw java.io.IOException("too big")
                                if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != lastPct) { lastPct = pct; download.value = Dl.Downloading(pct / 100f) } }
                            }
                        }
                    }
                }
                val apk = File(dir, "CDM-Plus-update.apk")
                if (!part.renameTo(apk)) throw java.io.IOException("rename")
                val err = verify(ctx, apk)
                if (err != null) { apk.delete(); download.value = Dl.Error(err) } else download.value = Dl.Ready(apk)
            } catch (e: CancellationException) {
                dir.deleteRecursively(); download.value = Dl.Idle; throw e
            } catch (e: Throwable) {
                dir.deleteRecursively()
                download.value = Dl.Error("Download failed. Check your connection and try again.")
            }
        }
    }

    fun cancelDownload() { dlJob?.cancel(); download.value = Dl.Idle }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signers(pi: PackageInfo?): Set<String> {
        pi ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners.toList() else (si.signingCertificateHistory?.toList().orEmpty() + si.apkContentsSigners.orEmpty())
        } else pi.signatures?.toList().orEmpty()
        return sigs.filterNotNull().map { sha256(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val mine = runCatching { pm.getPackageInfo(ctx.packageName, flags) }.getOrNull()
        val theirs = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
        val theirVc = theirs?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
        return UpdateCore.verifyApk(ctx.packageName, installedVersionCode(ctx), signers(mine), theirs?.packageName, theirVc, signers(theirs))
    }

    /** Opens Android's installer. Returns a message for the user when something is needed first. */
    fun install(ctx: Context, apk: File): String? {
        if (!apk.exists()) { download.value = Dl.Idle; return "The download is gone. Tap Download again." }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return "Allow \"Install unknown apps\" for CDM+, then come back and tap Install."
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        }.getOrElse { "Couldn't open the installer on this phone." }
    }
}

/** The once-a-day check (Android runs it when the phone has a network connection). */
class UpdateJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext as? CdmApp ?: return false
        job = app.appScope.launch {
            try { Updater.check(app, manual = false) } finally { runCatching { jobFinished(params, false) } }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
}
