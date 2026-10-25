package io.github.kyotobyte.cdmplus.engine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Tiny local HTTP/1.1 server: serves [data] with or without Range support. */
class TestServer(val data: ByteArray, val rangeSupport: Boolean, val bytesPerSecond: Int = 0, val slowFrom: Long = 0) : AutoCloseable {
    val rangedRequests = AtomicInteger(0)
    val requests = AtomicInteger(0)
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()

    init {
        pool.execute {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                pool.execute { handle(s) }
            }
        }
    }

    private fun handle(s: Socket) = s.use { sock ->
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("range:")) range = line.substringAfter(':').trim()
            }
            requests.incrementAndGet()
            if (!requestLine.contains("/file.bin")) {
                sock.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            var start = 0L
            var end = data.size - 1L
            val head = StringBuilder()
            if (rangeSupport && range != null && range.startsWith("bytes=")) {
                rangedRequests.incrementAndGet()
                val parts = range.removePrefix("bytes=").split("-")
                start = parts[0].toLong()
                if (parts.size > 1 && parts[1].isNotEmpty()) end = minOf(parts[1].toLong(), data.size - 1L)
                head.append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${data.size}\r\nAccept-Ranges: bytes\r\n")
            } else {
                head.append("HTTP/1.1 200 OK\r\n")
            }
            head.append("Content-Type: application/octet-stream\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
            val out = sock.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            var pos = start.toInt()
            val stop = end.toInt() + 1
            while (pos < stop) {
                val n = minOf(16 * 1024, stop - pos)
                out.write(data, pos, n)
                pos += n
                if (bytesPerSecond > 0 && pos >= slowFrom) Thread.sleep((n * 1000L) / bytesPerSecond)
            }
            out.flush()
        } catch (_: Exception) { }
    }

    val url: String get() = "http://127.0.0.1:${server.localPort}/file.bin"
    override fun close() { server.close(); pool.shutdownNow() }
}
