package io.github.kyotobyte.cdmplus.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UpdateTest {
    private val base = "https://github.com/KyotoByte/CDM-Plus-Downloader/releases/download/v1.1/CDM-Plus-Downloader-v1.1-"
    private val v11Json = """{"versionCode":12,"versionName":"1.1","notes":"Faster MEGA","apks":{
        "arm64-v8a":"${base}arm64-v8a.apk","armeabi-v7a":"${base}armeabi-v7a.apk",
        "x86_64":"${base}x86_64.apk","x86":"${base}x86.apk","universal":"${base}universal.apk"}}"""

    /** Tiny HTTP server: answers every request with [status] and [body], or never answers when [hang]. */
    private class Server(val status: Int, val body: String, val hang: Boolean = false) : AutoCloseable {
        val socket = ServerSocket(0)
        val url get() = "http://127.0.0.1:${socket.localPort}/update.json"
        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                            if (hang) { Thread.sleep(5000); return@use }
                            val b = body.toByteArray()
                            it.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray() + b)
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        override fun close() = socket.close()
    }

    private fun fastClient() = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()

    @Test fun badJsonIsIgnored() {
        listOf("", "   ", "not json", "{", "[1,2]", "{\"versionCode\":\"abc\"}", "{\"versionCode\":0}", "<html>404</html>", "{\"apks\":null}").forEach {
            assertNull(it, UpdateCore.parseUpdate(it))
            assertEquals(CheckResult.Failed, UpdateCore.evaluate(UpdateCore.parseUpdate(it), 11))
            assertNull(UpdateCore.parseNotice(it))
        }
    }

    @Test fun notFoundIsNull() = Server(404, "Not Found").use { assertNull(UpdateCore.fetchText(fastClient(), it.url)) }

    @Test fun serverErrorIsNull() = Server(500, "{}").use { assertNull(UpdateCore.fetchText(fastClient(), it.url)) }

    @Test fun timeoutIsNull() = Server(200, "{}", hang = true).use {
        val t0 = System.currentTimeMillis()
        assertNull(UpdateCore.fetchText(fastClient(), it.url))
        assertTrue(System.currentTimeMillis() - t0 < 4500)
    }

    @Test fun offlineIsNull() {
        val port = ServerSocket(0).use { it.localPort } // nothing listens here any more
        assertNull(UpdateCore.fetchText(fastClient(), "http://127.0.0.1:$port/update.json"))
    }

    @Test fun hugeBodyIsRefused() = Server(200, "x".repeat(200_000)).use { assertNull(UpdateCore.fetchText(fastClient(), it.url)) }

    @Test fun sameVersionIsLatest() {
        val info = UpdateCore.parseUpdate("""{"versionCode":11,"versionName":"1.0","apks":{}}""")
        assertEquals(CheckResult.Latest, UpdateCore.evaluate(info, 11))
        assertEquals(CheckResult.Latest, UpdateCore.evaluate(info, 12))
        val ui = UpdateCore.updateUi(info, 11, enabled = true, lastNotifiedVersionCode = 0, dismissedVersionCode = 0)
        assertFalse(ui.notify); assertFalse(ui.banner)
    }

    @Test fun newerVersionFromServerShowsNotificationAndBanner() = Server(200, v11Json).use { srv ->
        val info = UpdateCore.parseUpdate(UpdateCore.fetchText(fastClient(), srv.url))
        assertNotNull(info)
        val r = UpdateCore.evaluate(info, 11)
        assertTrue(r is CheckResult.Available)
        assertEquals(12L, info!!.versionCode)
        val ui = UpdateCore.updateUi(info, 11, enabled = true, lastNotifiedVersionCode = 0, dismissedVersionCode = 0)
        assertTrue(ui.notify); assertTrue(ui.banner)
        assertEquals("New update available: v1.1", UpdateCore.notificationTitle(info))
        // Notified once already: no second notification, banner stays until updated or closed.
        UpdateCore.updateUi(info, 11, true, lastNotifiedVersionCode = 12, dismissedVersionCode = 0).let { assertFalse(it.notify); assertTrue(it.banner) }
        // Banner closed for v1.1: hidden. A later v1.2 (13) shows again.
        UpdateCore.updateUi(info, 11, true, 12, dismissedVersionCode = 12).let { assertFalse(it.banner) }
        UpdateCore.updateUi(info.copy(versionCode = 13), 11, true, 12, 12).let { assertTrue(it.notify); assertTrue(it.banner) }
        // Switched off in Settings: nothing.
        UpdateCore.updateUi(info, 11, enabled = false, 0, 0).let { assertFalse(it.notify); assertFalse(it.banner) }
        // After installing v1.1: nothing.
        UpdateCore.updateUi(info, 12, true, 0, 0).let { assertFalse(it.notify); assertFalse(it.banner) }
    }

    @Test fun labels() {
        assertEquals("v1.1", UpdateCore.label(UpdateInfo(12, "1.1")))
        assertEquals("v2.0", UpdateCore.label(UpdateInfo(13, "v2.0")))
        assertEquals("build 14", UpdateCore.label(UpdateInfo(14, "")))
    }

    @Test fun picksApkForCpuTypeWithUniversalFallback() {
        val info = UpdateCore.parseUpdate(v11Json)!!
        assertEquals("${base}arm64-v8a.apk", UpdateCore.pickApk(info, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("${base}armeabi-v7a.apk", UpdateCore.pickApk(info, listOf("armeabi-v7a", "armeabi")))
        assertEquals("${base}x86.apk", UpdateCore.pickApk(info, listOf("x86")))
        // Wrong / unknown ABI: universal.
        assertEquals("${base}universal.apk", UpdateCore.pickApk(info, listOf("riscv64")))
        assertEquals("${base}universal.apk", UpdateCore.pickApk(info, emptyList()))
        val noUniversal = info.copy(apks = info.apks - "universal")
        assertNull(UpdateCore.pickApk(noUniversal, listOf("riscv64")))
        // Plain http links are never used.
        val insecure = info.copy(apks = mapOf("arm64-v8a" to "http://example.com/a.apk", "universal" to "${base}universal.apk"))
        assertEquals("${base}universal.apk", UpdateCore.pickApk(insecure, listOf("arm64-v8a")))
        assertNull(UpdateCore.pickApk(info.copy(apks = mapOf("universal" to "ftp://x/y.apk")), listOf("x86")))
    }

    @Test fun noticeShownOncePerId() {
        assertNull(UpdateCore.parseNotice("""{"id":"","title":"Hi","message":"x"}"""))
        assertNull(UpdateCore.parseNotice("""{"id":"n1","title":"  ","message":""}"""))
        val n = UpdateCore.parseNotice("""{"id":"n1","title":"Server move","message":"New mirror","url":"https://github.com/KyotoByte","extra":1}""")!!
        assertEquals("n1", n.id); assertEquals("https://github.com/KyotoByte", n.url)
        assertNull(UpdateCore.parseNotice("""{"id":"n2","title":"T","message":"M","url":"javascript:alert(1)"}""")!!.url)
        assertNull(UpdateCore.parseNotice("""{"id":"n2","title":"T","message":"M","url":""}""")!!.url)
        assertTrue(UpdateCore.shouldNotifyNotice("n1", null))
        assertFalse(UpdateCore.shouldNotifyNotice("n1", "n1"))
        assertTrue(UpdateCore.shouldNotifyNotice("n2", "n1"))
        assertFalse(UpdateCore.shouldNotifyNotice("", null))
    }

    @Test fun apkVerification() {
        val pkg = "io.github.kyotobyte.cdmplus"
        val ours = setOf("bd53a0cee393f24c01d67a1fcb865490bc349fc9a26f6196c9e522ab484fe815")
        assertNull(UpdateCore.verifyApk(pkg, 11, ours, pkg, 12, ours))
        assertNotNull(UpdateCore.verifyApk(pkg, 11, ours, null, 0, emptySet()))
        assertTrue(UpdateCore.verifyApk(pkg, 11, ours, pkg, 12, setOf("deadbeef"))!!.contains("Signature"))
        assertNotNull(UpdateCore.verifyApk(pkg, 11, ours, pkg, 12, emptySet()))
        assertNotNull(UpdateCore.verifyApk(pkg, 11, emptySet(), pkg, 12, ours))
        assertTrue(UpdateCore.verifyApk(pkg, 11, ours, "com.evil.app", 12, ours)!!.contains("different app"))
        assertNotNull(UpdateCore.verifyApk(pkg, 11, ours, pkg, 11, ours))
    }

    @Test fun openCheckIsThrottled() {
        val now = 1_000_000_000_000L
        assertTrue(UpdateCore.shouldCheckOnOpen(0, now))
        assertFalse(UpdateCore.shouldCheckOnOpen(now - 60_000, now))
        assertTrue(UpdateCore.shouldCheckOnOpen(now - UpdateCore.OPEN_CHECK_INTERVAL_MS, now))
        assertTrue(UpdateCore.shouldCheckOnOpen(now + 60_000, now)) // clock moved back
    }

    @Test fun shippedFilesParse() {
        val root = listOf(File("update.json"), File("../update.json")).first { it.exists() }.parentFile ?: File(".")
        val info = UpdateCore.parseUpdate(File(root, "update.json").readText())!!
        assertEquals(12L, info.versionCode) // V1.0 Patch 1
        assertEquals("1.0", info.versionName)
        assertEquals(CheckResult.Latest, UpdateCore.evaluate(info, 12))
        assertEquals(CheckResult.Available(info), UpdateCore.evaluate(info, 11)) // vc11 users get offered Patch 1
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal").forEach {
            assertEquals("https://github.com/KyotoByte/CDM-Plus-Downloader/releases/download/v1.0/CDM-Plus-Downloader-v1.0-$it.apk", info.apks[it])
        }
        assertEquals("patch1-2026-10-26", UpdateCore.parseNotice(File(root, "notice.json").readText())!!.id) // Patch 1 announcement
        assertEquals(CheckResult.Available(info), UpdateCore.evaluate(info, 10)) // v1.0 build 10 users get offered build 11
    }
}
