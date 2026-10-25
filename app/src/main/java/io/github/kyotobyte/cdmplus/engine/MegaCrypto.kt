package io.github.kyotobyte.cdmplus.engine

import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MEGA public-link crypto, written from MEGA's documented public API (no MEGA SDK code).
 * A file link key is 256 bits: AES key = first half XOR second half, CTR nonce = words 4-5,
 * the file data is AES-128-CTR and the attributes are AES-128-CBC with a zero IV ("MEGA{json}").
 */
object MegaCrypto {
    data class Link(val id: String, val key: ByteArray, val folder: Boolean = false)

    private val FILE = Regex("""(?i)mega(?:\.co)?\.nz/(?:file|embed)/([A-Za-z0-9_-]{8})#([A-Za-z0-9_,-]{43,44})""")
    private val LEGACY = Regex("""(?i)mega(?:\.co)?\.nz/(?:embed)?#!([A-Za-z0-9_-]{8})!([A-Za-z0-9_,-]{43,44})""")
    private val FOLDER = Regex("""(?i)mega(?:\.co)?\.nz/(?:folder/[A-Za-z0-9_-]{8}#|#F!)""")

    /** A public folder link: folder handle, 128-bit folder key, optional subfolder or file inside it. */
    class FolderLink(val handle: String, val key: ByteArray, val sub: String? = null, val file: String? = null)

    private val FOLDER_NEW = Regex("""(?i)mega(?:\.co)?\.nz/folder/([A-Za-z0-9_-]{8})#([A-Za-z0-9_,-]{22})(?:/folder/([A-Za-z0-9_-]{8}))?(?:/file/([A-Za-z0-9_-]{8}))?""")
    private val FOLDER_OLD = Regex("""(?i)mega(?:\.co)?\.nz/#F!([A-Za-z0-9_-]{8})!([A-Za-z0-9_,-]{22})(?:!([A-Za-z0-9_-]{8}))?(?:\?([A-Za-z0-9_-]{8}))?""")

    fun parseFolder(url: String): FolderLink? {
        val m = FOLDER_NEW.find(url) ?: FOLDER_OLD.find(url) ?: return null
        val key = runCatching { b64(m.groupValues[2]) }.getOrNull()?.takeIf { it.size == 16 } ?: return null
        return FolderLink(m.groupValues[1], key, m.groupValues[3].ifEmpty { null }, m.groupValues[4].ifEmpty { null })
    }

    /** The official link to one file inside a shared folder. */
    fun folderFileLink(f: FolderLink, node: String) = "https://mega.nz/folder/${f.handle}#" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(f.key) + "/file/$node"

    /**
     * A node's "k" field is "owner:key" (several joined with "/"). In a public folder the key whose owner is
     * a node of the shared tree is encrypted with the folder key (AES-128-ECB). Files have 32-byte keys, folders 16.
     */
    fun nodeKey(k: String, folderKey: ByteArray, knownOwners: Set<String>, file: Boolean): ByteArray? {
        val parts = k.split('/').mapNotNull { p -> p.indexOf(':').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }
        val want = if (file) 32 else 16
        val ordered = parts.filter { it.first in knownOwners } + parts.filter { it.first !in knownOwners }
        for ((_, enc) in ordered) {
            val raw = runCatching { b64(enc) }.getOrNull() ?: continue
            if (raw.size != want) continue
            val c = Cipher.getInstance("AES/ECB/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(folderKey, "AES"))
            return c.doFinal(raw)
        }
        return null
    }

    /** Name from a node's attributes; [nodeKey] is 32 bytes for files, 16 for folders. Null when the key is wrong. */
    fun nodeName(at: String, nodeKey: ByteArray): String? = if (nodeKey.size == 32) fileName(at, nodeKey) else attrName(at, nodeKey)

    fun isMega(url: String) = FILE.containsMatchIn(url) || LEGACY.containsMatchIn(url) || FOLDER.containsMatchIn(url)
    fun isFolder(url: String) = FOLDER.containsMatchIn(url) && !FILE.containsMatchIn(url)

    fun parse(url: String): Link? {
        val m = FILE.find(url) ?: LEGACY.find(url) ?: return null
        val key = runCatching { b64(m.groupValues[2]) }.getOrNull() ?: return null
        if (key.size != 32) return null
        return Link(m.groupValues[1], key)
    }

    fun b64(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s.replace(",", "").trimEnd('='))

    fun aesKey(k: ByteArray): ByteArray = ByteArray(16) { (k[it].toInt() xor k[it + 16].toInt()).toByte() }
    fun nonce(k: ByteArray): ByteArray = k.copyOfRange(16, 24)

    /** Decrypts the "at" attributes and returns the file name, or null when the key is wrong. */
    fun fileName(at: String, linkKey: ByteArray): String? = attrName(at, aesKey(linkKey))

    private fun attrName(at: String, aes: ByteArray): String? = runCatching {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(ByteArray(16)))
        val raw = b64(at)
        val plain = String(c.doFinal(raw.copyOf((raw.size + 15) / 16 * 16)), Charsets.UTF_8).trimEnd('\u0000')
        if (!plain.startsWith("MEGA{")) return null
        Regex("\"n\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(plain)?.groupValues?.get(1)
            ?.let { kotlinx.serialization.json.Json.decodeFromString<String>("\"$it\"") }
    }.getOrNull()

    /** CTR cipher positioned at [offset] (the counter is the 16-byte block index). */
    fun ctrAt(linkKey: ByteArray, offset: Long): Cipher {
        val iv = ByteBuffer.allocate(16).put(nonce(linkKey)).putLong(offset / 16).array()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey(linkKey), "AES"), IvParameterSpec(iv))
        val skip = (offset % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        return c
    }

    /** Decrypts a whole downloaded file in place (CTR keeps the length). */
    fun decryptInPlace(f: File, linkKey: ByteArray, onProgress: (Long) -> Unit = {}) {
        val c = ctrAt(linkKey, 0)
        RandomAccessFile(f, "rw").use { raf ->
            val buf = ByteArray(1 shl 20); var pos = 0L
            while (true) {
                raf.seek(pos)
                val n = raf.read(buf); if (n <= 0) break
                val out = c.update(buf, 0, n)
                raf.seek(pos); raf.write(out, 0, out.size)
                pos += n; onProgress(pos)
            }
        }
    }

    /** postProcess tag carried by the download item: "mega:<base64url link key>". */
    fun tag(linkKey: ByteArray) = "mega:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(linkKey)
    fun keyFromTag(tag: String?): ByteArray? = tag?.takeIf { it.startsWith("mega:") }?.let { runCatching { b64(it.removePrefix("mega:")) }.getOrNull() }?.takeIf { it.size == 32 }

    fun errorText(code: Int, what: String = "file"): String = when (code) {
        -17, -4 -> "MEGA transfer limit reached, try later"
        -9 -> "This MEGA $what doesn't exist or was removed"
        -11 -> "Access to this MEGA $what was denied"
        -16 -> "This MEGA $what was taken down"
        -18 -> "MEGA is busy right now, try again in a minute"
        -2 -> "Bad MEGA link"
        else -> "MEGA error $code"
    }

    @Suppress("unused") private fun hex(b: ByteArray) = BigInteger(1, b).toString(16)
}
