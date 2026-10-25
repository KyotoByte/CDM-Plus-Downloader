package io.github.kyotobyte.cdmplus.engine

import io.github.kyotobyte.cdmplus.grab.GrabException
import io.github.kyotobyte.cdmplus.grab.MegaGrabber
import io.github.kyotobyte.cdmplus.ui.folderSubDir
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class Build9Test {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val hisFolder = "https://mega.nz/folder/fwFnjKZJ#nyGkAA5kegQA0W1tG15W2w"
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    @Test fun folderLinkParsing() {
        val a = MegaCrypto.parseFolder(hisFolder)!!
        assertEquals("fwFnjKZJ", a.handle); assertEquals(16, a.key.size); assertNull(a.sub); assertNull(a.file)
        assertTrue(MegaCrypto.isFolder(hisFolder)); assertTrue(MegaGrabber.isSupported(hisFolder))
        val sub = MegaCrypto.parseFolder("see https://mega.nz/folder/fwFnjKZJ#nyGkAA5kegQA0W1tG15W2w/folder/yos33Q5S ok")!!
        assertEquals("yos33Q5S", sub.sub); assertNull(sub.file)
        val f = MegaCrypto.parseFolder("https://mega.nz/folder/fwFnjKZJ#nyGkAA5kegQA0W1tG15W2w/file/n49HxCpQ")!!
        assertEquals("n49HxCpQ", f.file); assertArrayEquals(a.key, f.key)
        val old = MegaCrypto.parseFolder("https://mega.nz/#F!fwFnjKZJ!nyGkAA5kegQA0W1tG15W2w")!!
        assertEquals("fwFnjKZJ", old.handle); assertArrayEquals(a.key, old.key)
        assertEquals("yos33Q5S", MegaCrypto.parseFolder("https://mega.co.nz/#F!fwFnjKZJ!nyGkAA5kegQA0W1tG15W2w!yos33Q5S")!!.sub)
        assertEquals("n49HxCpQ", MegaCrypto.parseFolder("https://mega.nz/#F!fwFnjKZJ!nyGkAA5kegQA0W1tG15W2w?n49HxCpQ")!!.file)
        assertNull(MegaCrypto.parseFolder("https://mega.nz/folder/fwFnjKZJ"))
        assertEquals(hisFolder + "/file/n49HxCpQ", MegaCrypto.folderFileLink(a, "n49HxCpQ"))
        // a file link is still a file link
        assertNull(MegaCrypto.parseFolder("https://mega.nz/file/hYVmXKqL#r0d0-WRnFwulR_shhuEDwrY1Vo103-am1MyUy8oV6Ps"))
    }

    private fun ecb(key: ByteArray, data: ByteArray) = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)
    private fun attrs(aes: ByteArray, name: String): String {
        val plain = "MEGA{\"n\":\"$name\"}".toByteArray().let { it.copyOf((it.size + 15) / 16 * 16) }
        return b64(Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(ByteArray(16))) }.doFinal(plain))
    }

    /** Offline: a made-up encrypted folder tree decrypts into names + relative paths; wrong key fails clearly. */
    @Test fun folderTreeDecrypt() {
        val rnd = Random(9); val fk = rnd.nextBytes(16)
        val link = MegaCrypto.FolderLink("AAAAAAAA", fk)
        fun dir(h: String, p: String, name: String): String { val k = rnd.nextBytes(16); return """{"h":"$h","p":"$p","t":1,"a":"${attrs(k, name)}","k":"ROOTROOT:${b64(ecb(fk, k))}"}""" }
        fun file(h: String, p: String, name: String, size: Long): String { val k = rnd.nextBytes(32); return """{"h":"$h","p":"$p","t":0,"s":$size,"a":"${attrs(MegaCrypto.aesKey(k), name)}","k":"OWNERXYZ:${b64(rnd.nextBytes(32))}/ROOTROOT:${b64(ecb(fk, k))}"}""" }
        val resp = "[{\"f\":[" + listOf(dir("ROOTROOT", "PARENT00", "Top Folder"), file("FILE0001", "ROOTROOT", "b.mp4", 2000), file("FILE0002", "ROOTROOT", "a é.txt", 5),
            dir("SUBDIR01", "ROOTROOT", "Sub"), file("FILE0003", "SUBDIR01", "c.zip", 70), dir("SUBDIR02", "SUBDIR01", "Deep"), file("FILE0004", "SUBDIR02", "d.pdf", 1)).joinToString(",") + "]}]"
        val folder = MegaGrabber.parseFolder(resp, link)
        val r = MegaGrabber.folderResult(folder, link)
        assertEquals("Top Folder", r.title)
        assertEquals(listOf("/a é.txt", "/b.mp4", "Sub/c.zip", "Sub/Deep/d.pdf"), r.folderFiles.map { it.path + "/" + it.name })
        assertEquals(listOf(5L, 2000L, 70L, 1L), r.folderFiles.map { it.bytes })
        assertTrue(r.folderFiles[0].link.endsWith("/file/FILE0002"))
        val sub = MegaGrabber.folderResult(folder, MegaCrypto.FolderLink("AAAAAAAA", fk, sub = "SUBDIR01"))
        assertEquals("Sub", sub.title); assertEquals(listOf("/c.zip", "Deep/d.pdf"), sub.folderFiles.map { it.path + "/" + it.name })
        val wrong = runCatching { MegaGrabber.parseFolder(resp, MegaCrypto.FolderLink("AAAAAAAA", Random(1).nextBytes(16))) }.exceptionOrNull()
        assertTrue(wrong is GrabException && wrong.message!!.contains("Wrong key"))
        val quota = runCatching { MegaGrabber.parseFolder("[-17]", link) }.exceptionOrNull()
        assertEquals("MEGA transfer limit reached, try later", quota?.message)
        assertEquals("This MEGA folder doesn't exist or was removed", runCatching { MegaGrabber.parseFolder("-9", link) }.exceptionOrNull()?.message)
        assertEquals("This MEGA folder is empty", runCatching { MegaGrabber.folderResult(MegaGrabber.parseFolder("[{\"f\":[]}]", link), link) }.exceptionOrNull()?.message)
    }

    @Test fun savedFolderPaths() {
        assertEquals("AE Pixel Sorter", folderSubDir("AE Pixel Sorter", ""))
        assertEquals("My_Folder/Sub/Deep", folderSubDir("My/Folder", "Sub/Deep"))
        assertEquals("Top/a/b_c", SegmentedDownloader.sanitizeDir("Top/../a/./b:c/"))
        assertNull(SegmentedDownloader.sanitizeDir("../.."))
        assertNull(SegmentedDownloader.sanitizeDir(null))
    }

    /** Live: his folder link -> listing -> one small file -> 4-connection download -> AES-CTR decrypt -> valid file. */
    @Test fun megaLiveHisFolder() = runBlocking<Unit> {
        val r = MegaGrabber.grab(hisFolder, client)
        println("MEGA FOLDER: \"${r.title}\" ${r.folderFiles.size} files")
        r.folderFiles.forEach { println("  ${if (it.path.isEmpty()) "" else it.path + "/"}${it.name}  ${it.bytes} B") }
        assertTrue(r.folderFiles.isNotEmpty()); assertTrue(r.items.isEmpty())
        val small = r.folderFiles.filter { it.bytes in 1..5_000_000 }.minByOrNull { it.bytes }!!
        val one = MegaGrabber.grab(small.link, client)
        val o = one.items.single().options.single()
        assertEquals(small.name, o.fileName); assertEquals(small.bytes, o.bytes)
        val dest = File.createTempFile("megafolder", ".bin").also { it.delete() }
        val done = SegmentedDownloader(client, minSegmentBytes = 1024).download(DownloadItem(id = "mf", url = o.url, fileName = o.fileName!!, threads = 4), dest) {}
        assertEquals(Status.DONE, done.status); assertEquals(o.bytes, dest.length())
        MegaCrypto.decryptInPlace(dest, MegaCrypto.keyFromTag(o.postProcess)!!)
        val out = File("/tmp/megafolder_out_" + o.fileName!!.replace(' ', '_')); dest.copyTo(out, true); dest.delete()
        val head = out.readBytes().copyOf(8)
        println("MEGA FOLDER file: ${o.fileName} ${out.length()} B, segments=${done.segments.size}, magic=${head.joinToString(" ") { "%02x".format(it) }} -> $out")
        val type = FileNamer.afterDownload("x.bin", head)
        assertTrue("decrypted data must be a real file type, got $type", type != "x.bin")
    }
}
