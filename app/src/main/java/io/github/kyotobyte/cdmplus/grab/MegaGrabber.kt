package io.github.kyotobyte.cdmplus.grab

import io.github.kyotobyte.cdmplus.engine.FileNamer
import io.github.kyotobyte.cdmplus.engine.MegaCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** MEGA file and folder links: asks MEGA's public API for the download URL, decrypts the name; the engine decrypts the data. */
object MegaGrabber {
    private val APIS = listOf("https://g.api.mega.co.nz/cs", "https://g.api.mega.nz/cs")
    private val seq = java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000)

    fun isSupported(url: String) = MegaCrypto.isMega(url)

    /** One node of a shared folder after decryption. */
    class Node(val h: String, val parent: String, val folder: Boolean, val name: String, val size: Long, val key: ByteArray)
    class Folder(val link: MegaCrypto.FolderLink, val nodes: List<Node>, val at: Long = System.currentTimeMillis())

    private val folders = java.util.concurrent.ConcurrentHashMap<String, Folder>()

    private fun call(body: String, client: OkHttpClient, folderHandle: String?): String {
        var lastErr: Throwable? = null
        for (api in APIS) {
            val q = "$api?id=${seq.incrementAndGet()}" + (folderHandle?.let { "&n=$it" } ?: "")
            try {
                return client.newCall(Request.Builder().url(q).post(body.toRequestBody("application/json".toMediaType())).build())
                    .execute().use { r -> if (r.code == 509) throw GrabException(MegaCrypto.errorText(-17)); r.body?.string() ?: "" }
            } catch (e: GrabException) { throw e } catch (e: Exception) { lastErr = e }
        }
        throw GrabException("Couldn't reach MEGA (${lastErr?.message ?: "network"})")
    }

    private fun firstObject(resp: String, what: String): JsonObject {
        val el = runCatching { Json.parseToJsonElement(resp) }.getOrNull() ?: throw GrabException("MEGA sent an unexpected answer")
        (el as? JsonPrimitive)?.intOrNull?.let { throw GrabException(MegaCrypto.errorText(it, what)) }
        val first = (el as? JsonArray)?.firstOrNull() ?: throw GrabException("MEGA sent an unexpected answer")
        (first as? JsonPrimitive)?.intOrNull?.let { throw GrabException(MegaCrypto.errorText(it, what)) }
        val o = first as? JsonObject ?: throw GrabException("MEGA sent an unexpected answer")
        (o["e"] as? JsonPrimitive)?.intOrNull?.let { throw GrabException(MegaCrypto.errorText(it, what)) }
        return o
    }

    /** Decrypts a folder listing ("a":"f" answer). Nodes whose key or name can't be decrypted are skipped. */
    fun parseFolder(resp: String, link: MegaCrypto.FolderLink): Folder {
        val o = firstObject(resp, "folder")
        val f = (o["f"] as? JsonArray) ?: throw GrabException("MEGA sent an unexpected answer")
        val raw = f.mapNotNull { it as? JsonObject }
        val handles = raw.mapNotNull { (it["h"] as? JsonPrimitive)?.content }.toSet()
        val nodes = raw.mapNotNull { n ->
            val t = (n["t"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (t != 0 && t != 1) return@mapNotNull null
            val h = (n["h"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val key = MegaCrypto.nodeKey((n["k"] as? JsonPrimitive)?.content ?: "", link.key, handles, t == 0) ?: return@mapNotNull null
            val name = MegaCrypto.nodeName((n["a"] as? JsonPrimitive)?.content ?: "", key) ?: return@mapNotNull null
            Node(h, (n["p"] as? JsonPrimitive)?.content ?: "", t == 1, name, (n["s"] as? JsonPrimitive)?.longOrNull ?: 0, key)
        }
        if (nodes.isEmpty() && raw.isNotEmpty()) throw GrabException("Wrong key in this MEGA folder link (nothing can be decrypted)")
        return Folder(link, nodes)
    }

    /** Files under [root] with their folder path relative to it, sorted like a file manager. */
    fun filesUnder(folder: Folder, root: String): List<Pair<String, Node>> {
        val kids = folder.nodes.groupBy { it.parent }
        val out = ArrayList<Pair<String, Node>>()
        fun walk(h: String, path: String, depth: Int) {
            if (depth > 64) return
            val c = kids[h].orEmpty()
            c.filter { !it.folder }.sortedBy { it.name.lowercase() }.forEach { out += path to it }
            c.filter { it.folder }.sortedBy { it.name.lowercase() }.forEach { walk(it.h, if (path.isEmpty()) it.name else "$path/${it.name}", depth + 1) }
        }
        walk(root, "", 0)
        return out
    }

    /** The shared top folder: the folder node whose parent isn't in the listing. */
    fun topNode(folder: Folder): Node? {
        val hs = folder.nodes.map { it.h }.toSet()
        return folder.nodes.firstOrNull { it.folder && it.parent !in hs } ?: folder.nodes.firstOrNull { it.parent !in hs }
    }

    private fun loadFolder(link: MegaCrypto.FolderLink, client: OkHttpClient): Folder {
        folders[link.handle]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 && it.link.key.contentEquals(link.key) }?.let { return it }
        val resp = call("""[{"a":"f","c":1,"r":1,"ca":1}]""", client, link.handle)
        return parseFolder(resp, link).also { folders[link.handle] = it }
    }

    /** Lists a folder link as a pick list, or resolves a single file inside a folder (/folder/x#key/file/y). */
    fun folderResult(folder: Folder, link: MegaCrypto.FolderLink): GrabResult {
        val top = topNode(folder) ?: throw GrabException("This MEGA folder is empty")
        val root = link.sub?.let { s -> folder.nodes.firstOrNull { it.h == s && it.folder } ?: throw GrabException("That subfolder isn't in this MEGA folder") } ?: top
        val files = if (!root.folder) listOf("" to root) else filesUnder(folder, root.h)
        if (files.isEmpty()) throw GrabException("This MEGA folder is empty")
        return GrabResult("MEGA", root.name, folderFiles = files.map { (path, n) -> FolderFile(path, n.name, n.size, MegaCrypto.folderFileLink(link, n.h)) })
    }

    suspend fun grab(url: String, client: OkHttpClient): GrabResult = withContext(Dispatchers.IO) {
        MegaCrypto.parseFolder(url)?.let { link ->
            val folder = loadFolder(link, client)
            val fileHandle = link.file ?: link.sub?.takeIf { s -> folder.nodes.any { it.h == s && !it.folder } }
            if (fileHandle == null) return@withContext folderResult(folder, link)
            val node = folder.nodes.firstOrNull { it.h == fileHandle && !it.folder } ?: throw GrabException("That file isn't in this MEGA folder any more")
            val resp = call("""[{"a":"g","g":1,"ssl":2,"n":"${node.h}"}]""", client, link.handle)
            return@withContext fileOption(firstObject(resp, "file"), node.name, node.key, node.size)
        }
        if (MegaCrypto.isFolder(url)) throw GrabException("This MEGA folder link has no decryption key (the part after #). Copy the full link.")
        val link = MegaCrypto.parse(url) ?: throw GrabException("This MEGA link has no decryption key (the part after #). Copy the full link.")
        parse(call("""[{"a":"g","g":1,"ssl":2,"p":"${link.id}"}]""", client, null), link, url)
    }

    private fun fileOption(o: JsonObject, name: String, key: ByteArray, size0: Long): GrabResult {
        val g = (o["g"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") } ?: throw GrabException("MEGA didn't give a download link for this file")
        val size = (o["s"] as? JsonPrimitive)?.longOrNull ?: size0
        val ext = name.substringAfterLast('.', "").lowercase().ifBlank { "bin" }
        val kind = when (FileNamer.extToMime[ext]?.substringBefore('/')) { "video" -> "video"; "audio" -> "audio"; "image" -> "image"; else -> "file" }
        val opt = GrabOption(g, kind, "Original file", bytes = size, ext = ext, mime = FileNamer.extToMime[ext], postProcess = MegaCrypto.tag(key), fileName = name)
        return GrabResult("MEGA", name.substringBeforeLast('.'), items = listOf(GrabItem(0, listOf(opt))))
    }

    fun parse(resp: String, link: MegaCrypto.Link, url: String): GrabResult {
        val o = firstObject(resp, "file")
        val at = (o["at"] as? JsonPrimitive)?.content ?: ""
        val name = MegaCrypto.fileName(at, link.key) ?: throw GrabException("Wrong key in this MEGA link (the name can't be decrypted)")
        return fileOption(o, name, link.key, -1)
    }
}
