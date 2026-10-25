package io.github.kyotobyte.cdmplus.engine

import io.github.kyotobyte.cdmplus.grab.GrabException
import io.github.kyotobyte.cdmplus.grab.MegaGrabber
import io.github.kyotobyte.cdmplus.service.DisplayRate
import io.github.kyotobyte.cdmplus.ui.IncognitoJar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class Build8Test {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val publicLink = "https://mega.nz/#!hYVmXKqL!r0d0-WRnFwulR_shhuEDwrY1Vo103-am1MyUy8oV6Ps"

    @Test fun highestRefreshAtSameResolution() {
        val modes = listOf(Triple(1, 1080 to 2400, 60f), Triple(2, 1080 to 2400, 120f), Triple(3, 1440 to 3200, 144f), Triple(4, 1080 to 2400, 90f))
        assertEquals(2, DisplayRate.pickBest(1080, 2400, modes)) // 120 Hz, never switches to the 1440p 144 Hz mode
        assertEquals(3, DisplayRate.pickBest(1440, 3200, modes))
        assertNull(DisplayRate.pickBest(720, 1600, modes))
        assertEquals(1, DisplayRate.pickBest(1080, 2400, listOf(Triple(1, 1080 to 2400, 60f))))
    }

    @Test fun refreshToggleIsGone() {
        val src = File("src/main/java/io/github/kyotobyte/cdmplus")
        val all = src.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(all.contains("High refresh rate (auto)"))
        assertFalse(all.contains("highRefresh"))
    }

    @Test fun manifestKeepsStateOnRotationAndFold() {
        val mf = File("src/main/AndroidManifest.xml").readText()
        val cc = Regex("configChanges=\"([^\"]+)\"").find(mf)!!.groupValues[1].split('|').toSet()
        assertTrue(cc.containsAll(listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout", "uiMode", "density")))
        assertTrue(mf.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(mf.contains("FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(mf.contains("POST_NOTIFICATIONS"))
        assertTrue(mf.contains("enableOnBackInvokedCallback=\"true\""))
        assertFalse("internal files no longer shareable", File("src/main/res/xml/file_paths.xml").readText().contains("<files-path"))
    }

    @Test fun incognitoCookieExpiry() {
        val lines = IncognitoJar.expiryLines("sid=abc; _ga=GA1.2; theme=dark", "m.example.com")
        assertEquals(9, lines.size)
        assertTrue(lines.contains("sid=; Max-Age=0; Path=/; Domain=.example.com"))
        assertTrue(IncognitoJar.expiryLines(null, "x.com").isEmpty())
    }

    @Test fun megaLinkParsing() {
        val a = MegaCrypto.parse(publicLink)!!
        assertEquals("hYVmXKqL", a.id); assertEquals(32, a.key.size)
        val b = MegaCrypto.parse("Look https://mega.nz/file/hYVmXKqL#r0d0-WRnFwulR_shhuEDwrY1Vo103-am1MyUy8oV6Ps thanks")!!
        assertArrayEquals(a.key, b.key)
        assertNull(MegaCrypto.parse("https://mega.nz/file/hYVmXKqL"))
        assertTrue(MegaCrypto.isFolder("https://mega.nz/folder/abcdefgh#0123456789abcdefABCDEF"))
        assertTrue(MegaGrabber.isSupported("https://mega.nz/folder/abcdefgh#0123456789abcdefABCDEF"))
        assertFalse(MegaGrabber.isSupported("https://example.com/file.zip"))
        assertEquals("MEGA transfer limit reached, try later", MegaCrypto.errorText(-17))
        assertEquals("MEGA transfer limit reached, try later", DownloadErrors.message(HttpStatusException(509)))
        val quota = runCatching { MegaGrabber.parse("[-17]", a, publicLink) }.exceptionOrNull()
        assertTrue(quota is GrabException && quota.message == "MEGA transfer limit reached, try later")
        val tag = MegaCrypto.tag(a.key); assertArrayEquals(a.key, MegaCrypto.keyFromTag(tag))
    }

    @Test fun megaCtrSeekMatchesStream() {
        val key = Random(3).nextBytes(32); val data = Random(4).nextBytes(100_003)
        val enc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(MegaCrypto.aesKey(key), "AES"), IvParameterSpec(MegaCrypto.nonce(key) + ByteArray(8)))
        }.doFinal(data)
        for (off in listOf(0L, 15L, 16L, 4097L, 99_999L)) {
            val part = MegaCrypto.ctrAt(key, off).doFinal(enc.copyOfRange(off.toInt(), enc.size))
            assertArrayEquals("offset $off", data.copyOfRange(off.toInt(), data.size), part)
        }
        val f = File.createTempFile("mega", ".bin").apply { writeBytes(enc) }
        MegaCrypto.decryptInPlace(f, key); assertArrayEquals(data, f.readBytes()); f.delete()
    }

    /** Live: real public MEGA file -> API -> name -> multi-connection download -> decrypt -> check the file. */
    @Test fun megaLivePublicFile() = runBlocking<Unit> {
        val r = MegaGrabber.grab(publicLink, client)
        val o = r.items.single().options.single()
        println("MEGA: name=${o.fileName} size=${o.bytes} kind=${o.kind}")
        assertNotNull(o.fileName); assertTrue(o.bytes > 0)
        val dest = File.createTempFile("megadl", ".bin").also { it.delete() }
        val d = SegmentedDownloader(client, minSegmentBytes = 2048)
        val done = d.download(DownloadItem(id = "m", url = o.url, fileName = o.fileName!!, threads = 4), dest) {}
        assertEquals(Status.DONE, done.status); assertEquals(o.bytes, dest.length())
        MegaCrypto.decryptInPlace(dest, MegaCrypto.keyFromTag(o.postProcess)!!)
        val head = dest.readBytes().copyOf(16)
        val type = FileNamer.afterDownload("x.bin", head)
        println("MEGA decrypted: ${dest.length()} bytes, magic=${head.joinToString(" ") { "%02x".format(it) }}, detected=$type, saved as ${done.fileName}")
        File("/tmp/mega_out_" + o.fileName).let { dest.copyTo(it, true) }
        assertTrue("decrypted data must be a real file type, got $type", type != "x.bin")
        assertEquals(o.ext, type.substringAfterLast('.'))
        dest.delete()
    }
}
