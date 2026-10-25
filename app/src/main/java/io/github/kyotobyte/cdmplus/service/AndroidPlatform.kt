package io.github.kyotobyte.cdmplus.service

import android.provider.DocumentsContract

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.MainActivity
import io.github.kyotobyte.cdmplus.R
import io.github.kyotobyte.cdmplus.engine.DownloadItem
import io.github.kyotobyte.cdmplus.engine.DownloadPlatform
import io.github.kyotobyte.cdmplus.engine.Status
import java.io.File

class AndroidPlatform(private val app: CdmApp) : DownloadPlatform {

    override fun partialDir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial").apply { mkdirs() }

    override suspend fun publish(item: DownloadItem, file: File): Pair<String?, String?> {
        val mime = item.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val tree = app.settings.value.saveTreeUri
        if (tree.isNotBlank()) {
            // User-picked folder (SD card or anywhere) through the Storage Access Framework.
            val ok = runCatching {
                val treeUri = Uri.parse(tree)
                var parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))
                item.subDir?.split('/')?.forEach { seg -> parent = safChildDir(treeUri, parent, seg) }
                val doc = android.provider.DocumentsContract.createDocument(app.contentResolver, parent, mime, item.fileName) ?: error("create failed")
                app.contentResolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                file.delete()
                doc.toString()
            }.getOrNull()
            if (ok != null) return ok to null
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CDM+" + (item.subDir?.let { "/$it" } ?: ""))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create file in Downloads")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            uri.toString() to null
        } else {
            val canWrite = ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            @Suppress("DEPRECATION")
            val dir = if (canWrite) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CDM+")
                      else File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "CDM+")
            val sub = item.subDir?.let { File(dir, it) } ?: dir
            sub.mkdirs()
            val target = uniqueFile(sub, item.fileName)
            if (!file.renameTo(target)) { file.copyTo(target, overwrite = true); file.delete() }
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", target)
            uri.toString() to target.absolutePath
        }
    }

    /** Finds or creates the sub-folder [name] under a Storage Access Framework folder. */
    private fun safChildDir(treeUri: Uri, parent: Uri, name: String): Uri {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        app.contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
        }
        return DocumentsContract.createDocument(app.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: error("create folder failed")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++ }
        return f
    }

    override fun deletePublished(item: DownloadItem) {
        runCatching {
            if (item.savedPath != null) File(item.savedPath).delete()
            else item.savedUri?.let {
                val u = Uri.parse(it)
                if (android.provider.DocumentsContract.isDocumentUri(app, u)) android.provider.DocumentsContract.deleteDocument(app.contentResolver, u)
                else app.contentResolver.delete(u, null, null)
            }
        }
    }

    override fun networkBlockReason(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Waiting for network"
        if (app.settings.value.wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Waiting for Wi-Fi"
        }
        val stop = app.settings.value.batteryStop
        if (stop > 0) {
            val bm = app.getSystemService(android.os.BatteryManager::class.java)
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            if (bm != null && !bm.isCharging && level in 0 until stop) return "Paused: battery below $stop%"
        }
        return null
    }

    override fun onActiveChanged(active: Boolean) {
        if (active && !DownloadService.running) {
            runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        }
    }

    override fun onFinished(item: DownloadItem) {
        if (!app.settings.value.notifyOnComplete) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(app, item.id.hashCode(), Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ok = item.status == Status.DONE
        val n = NotificationCompat.Builder(app, CdmApp.CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (ok) "Download complete" else "Download failed")
            .setContentText(if (ok) item.fileName else "${item.fileName}: ${item.error ?: "error"}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), n)
    }

    override fun schedule(item: DownloadItem) {
        val at = item.scheduledAt ?: return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, item.id.hashCode(), Intent(app, ScheduleReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun mux(video: File, audio: File, out: File): Boolean = MediaTools.mux(video, audio, out)
    override fun extractAudio(input: File, out: File): String? = MediaTools.extractAudio(input, out)

    override suspend fun publishTree(item: DownloadItem, root: File): Pair<String?, String?> {
        if (root.isFile) return publish(item, root)
        var first: String? = null
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.parentFile!!.relativeTo(root.parentFile!!).path
            val u = publishLoose(f, rel)
            if (first == null) first = u
        }
        return null to "Download/CDM+/${root.name}"
    }

    /** Saves an extracted / torrent file into Downloads/CDM+/<relDir>/ (MediaStore on 10+). */
    fun publishLoose(file: java.io.File, relDir: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/CDM+/" + relDir)
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            app.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CDM+/$relDir").apply { mkdirs() }
            val f = java.io.File(dir, file.name); file.copyTo(f, overwrite = true); f.absolutePath
        }
    }.getOrNull()
}
