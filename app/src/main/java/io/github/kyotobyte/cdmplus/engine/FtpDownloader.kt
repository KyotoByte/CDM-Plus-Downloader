package io.github.kyotobyte.cdmplus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class FtpException(msg: String) : IOException(msg)

/**
 * Small FTP client written for CDM+ (passive mode, binary, REST resume). Multi-connection:
 * each part opens its own control + data connection and starts at its offset with REST.
 * ftp://user:pass@host:port/path — anonymous when no user is given.
 */
class FtpDownloader(private val limiter: SpeedLimiter, private val timeoutMs: Int = 20_000) {

    class Session(host: String, port: Int, private val timeout: Int) : AutoCloseable {
        private val sock = Socket().apply { connect(InetSocketAddress(host, port), timeout); soTimeout = timeout }
        private val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        private val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.ISO_8859_1)
        val host: String = host

        init { expect(reply(), 220) }

        /** Reads one (possibly multi-line) reply: returns code + text. */
        fun reply(): Pair<Int, String> {
            var line = reader.readLine() ?: throw FtpException("FTP server closed the connection")
            val code = line.take(3).toIntOrNull() ?: throw FtpException("Bad FTP reply: $line")
            val sb = StringBuilder(line)
            if (line.length > 3 && line[3] == '-') {
                while (true) {
                    line = reader.readLine() ?: break
                    sb.append('\n').append(line)
                    if (line.startsWith("$code ")) break
                }
            }
            return code to sb.toString()
        }

        fun cmd(c: String): Pair<Int, String> { writer.write(c + "\r\n"); writer.flush(); return reply() }

        fun expect(r: Pair<Int, String>, vararg ok: Int): Pair<Int, String> {
            if (r.first !in ok) throw FtpException("FTP: ${r.second.lineSequence().first()}")
            return r
        }

        fun login(user: String, pass: String) {
            val r = cmd("USER $user")
            if (r.first == 331 || r.first == 332) expect(cmd("PASS $pass"), 230, 202)
            else expect(r, 230)
            expect(cmd("TYPE I"), 200)
        }

        /** Opens a passive data connection (EPSV, falling back to PASV). */
        fun openData(): Socket {
            val e = cmd("EPSV")
            val addr = if (e.first == 229) {
                val port = Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(e.second)?.groupValues?.get(1)?.toInt() ?: throw FtpException("Bad EPSV reply")
                InetSocketAddress(host, port)
            } else {
                val p = expect(cmd("PASV"), 227)
                val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(p.second)?.groupValues?.drop(1)?.map { it.toInt() } ?: throw FtpException("Bad PASV reply")
                // Use the control host: servers behind NAT often report a private address.
                InetSocketAddress(host, n[4] * 256 + n[5])
            }
            return Socket().apply { connect(addr, timeout); soTimeout = timeout; receiveBufferSize = 512 * 1024 }
        }

        override fun close() {
            runCatching { writer.write("QUIT\r\n"); writer.flush() }
            runCatching { sock.close() }
        }
    }

    data class Target(val host: String, val port: Int, val user: String, val pass: String, val path: String)

    companion object {
        fun parse(url: String): Target {
            val u = URI(url)
            require(u.scheme.equals("ftp", true)) { "not an ftp:// link" }
            val info = u.rawUserInfo
            val user = info?.substringBefore(':')?.let { URLDecoder.decode(it, "UTF-8") } ?: "anonymous"
            val pass = info?.substringAfter(':', "")?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { info.contains(':') } ?: "cdmplus@example.com"
            return Target(u.host, if (u.port > 0) u.port else 21, user, pass, URLDecoder.decode(u.rawPath.ifEmpty { "/" }, "UTF-8"))
        }
    }

    fun open(t: Target) = Session(t.host, t.port, timeoutMs).also { it.login(t.user, t.pass) }

    /** File size via SIZE (null if the server doesn't say). */
    fun size(t: Target): Long? = open(t).use { s -> s.cmd("SIZE ${t.path}").takeIf { it.first == 213 }?.second?.substring(4)?.trim()?.toLongOrNull() }

    suspend fun download(item: DownloadItem, dest: File, onProgress: suspend (DownloadItem) -> Unit): DownloadItem = withContext(Dispatchers.IO) {
        val t = parse(item.url)
        var it0 = item
        if (it0.segments.isEmpty() || it0.totalBytes <= 0) {
            val len = size(t) ?: -1L
            // Anonymous FTP servers often cap connections per address: at most 4 parts.
            val parts = if (len > 2L * 1024 * 1024) minOf(item.threads, 4) else 1
            val segs = if (len <= 0 || parts == 1) listOf(Segment(0, 0, if (len > 0) len - 1 else -1)) else {
                val size = len / parts
                (0 until parts).map { i -> Segment(i, i * size, if (i == parts - 1) len - 1 else (i + 1) * size - 1) }
            }
            it0 = it0.copy(totalBytes = len, segments = segs, supportsRange = parts > 1, downloadedBytes = 0)
            dest.parentFile?.mkdirs(); dest.delete()
        }
        val segs = it0.segments.toMutableList()
        val doneBytes = Array(segs.size) { AtomicLong(segs[it].done) }
        RandomAccessFile(dest, "rw").use { raf ->
            if (it0.totalBytes > 0 && raf.length() < it0.totalBytes) raf.setLength(it0.totalBytes)
            val ch = raf.channel
            fun snap(): DownloadItem {
                val s = segs.mapIndexed { i, g -> g.copy(done = doneBytes[i].get()) }
                return it0.copy(segments = s, downloadedBytes = s.sumOf { it.done })
            }
            coroutineScope {
                val reporter = async {
                    while (true) { kotlinx.coroutines.delay(400); onProgress(snap()) }
                }
                try {
                    segs.indices.filter { !segs[it].isComplete }.map { i ->
                        async(Dispatchers.IO) {
                            val g = segs[i]
                            open(t).use { s ->
                                val offset = g.start + doneBytes[i].get()
                                if (offset > 0) s.expect(s.cmd("REST $offset"), 350)
                                val data = s.openData()
                                data.use { d ->
                                    s.expect(s.cmd("RETR ${t.path}"), 125, 150)
                                    val input = d.getInputStream()
                                    val buf = ByteArray(128 * 1024)
                                    var pos = offset
                                    val limit = if (g.end >= 0) g.end + 1 else Long.MAX_VALUE
                                    while (pos < limit) {
                                        coroutineContext.ensureActive()
                                        val want = minOf(buf.size.toLong(), limit - pos).toInt()
                                        val n = input.read(buf, 0, want)
                                        if (n < 0) break
                                        limiter.acquire(n)
                                        if (item.speedLimitBytes > 0) kotlinx.coroutines.delay(n * 1000L / item.speedLimitBytes)
                                        ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                                        pos += n
                                        doneBytes[i].addAndGet(n.toLong())
                                    }
                                    if (g.end >= 0 && pos < limit) throw FtpException("FTP transfer ended early")
                                }
                                // A part that stopped before the end of the file: the server may
                                // answer 426 (aborted) - fine. A whole-file part ends with 226.
                                runCatching { s.reply() }
                            }
                        }
                    }.awaitAll()
                } finally {
                    reporter.cancel()
                }
            }
            val fin = snap()
            val total = if (fin.totalBytes > 0) fin.totalBytes else fin.downloadedBytes
            if (fin.totalBytes > 0 && fin.downloadedBytes != fin.totalBytes) throw IOException("Size mismatch: ${fin.downloadedBytes} of ${fin.totalBytes}")
            if (fin.totalBytes <= 0) raf.setLength(fin.downloadedBytes)
            fin.copy(totalBytes = total, status = Status.DONE, finishedAt = System.currentTimeMillis()).also { onProgress(it) }
        }
    }
}
