package io.github.kyotobyte.cdmplus.vpn

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Encrypted DNS upstreams. Bootstrap IPs mean we never need plain DNS to find the resolver. */
enum class Upstream(val id: String, val title: String, val subtitle: String, val url: String, val bootstrap: List<String>) {
    CLOUDFLARE("cf", "1.1.1.1", "Cloudflare, fast + private", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")),
    CLOUDFLARE_MALWARE("cf2", "1.1.1.2", "Cloudflare, blocks malware", "https://security.cloudflare-dns.com/dns-query", listOf("1.1.1.2", "1.0.0.2", "2606:4700:4700::1112")),
    CLOUDFLARE_FAMILY("cf3", "1.1.1.3", "Cloudflare, blocks malware + adult", "https://family.cloudflare-dns.com/dns-query", listOf("1.1.1.3", "1.0.0.3", "2606:4700:4700::1113")),
    ADGUARD("adguard", "AdGuard DNS", "Blocks ads + trackers at the resolver", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
    QUAD9("quad9", "Quad9", "Blocks malicious domains", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"));

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: CLOUDFLARE }
}

/** DNS-over-HTTPS (RFC 8484, POST application/dns-message). */
class DohResolver(val upstream: Upstream, base: OkHttpClient? = null) {
    private val host = upstream.url.substringAfter("://").substringBefore('/')
    private val bootstrap: List<InetAddress> = upstream.bootstrap.map { InetAddress.getByName(it) }.sortedBy { if (it is Inet4Address) 0 else 1 }
    private val mediaType = "application/dns-message".toMediaType()

    val client: OkHttpClient = (base?.newBuilder() ?: OkHttpClient.Builder())
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(host, true)) bootstrap else Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Fallback client: HTTP/1.1 only (a few resolver front-ends answer 505 to some HTTP/2 requests). */
    private val http1: OkHttpClient by lazy { client.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build() }

    /** Sends the raw DNS message, returns the raw answer (same id as the query). One retry over HTTP/1.1. */
    fun resolve(query: ByteArray): ByteArray = try { resolveWith(client, query) } catch (e: java.io.IOException) { resolveWith(http1, query) }

    private fun resolveWith(client: OkHttpClient, query: ByteArray): ByteArray {
        val id0 = query[0]; val id1 = query[1]
        // RFC 8484 recommends id 0 for HTTP caching; restore the caller's id after.
        val q = query.copyOf().also { it[0] = 0; it[1] = 0 }
        val req = Request.Builder().url(upstream.url)
            .header("Accept", "application/dns-message")
            .post(q.toRequestBody(mediaType)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("DoH HTTP ${r.code} from ${upstream.title}")
            val body = r.body?.bytes() ?: throw java.io.IOException("empty DoH answer")
            if (body.size < 12) throw java.io.IOException("short DoH answer")
            body[0] = id0; body[1] = id1
            return body
        }
    }

    /** Address lookup over DoH: used so CDM+'s own downloads also use encrypted DNS. */
    fun lookup(name: String): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        for (t in intArrayOf(DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA)) {
            runCatching {
                DnsMessage.answers(resolve(DnsMessage.query(name, t, 0x1234)))
                    .filter { it.type == t }.forEach { out += InetAddress.getByAddress(name, it.data) }
            }
        }
        return out
    }
}

/** OkHttp resolver for the download engine: encrypted lookups, falling back to the system resolver. */
class DohDns(private val doh: () -> DohResolver?) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val r = doh()
        if (r != null && !hostname.all { it.isDigit() || it == '.' || it == ':' }) {
            val res = runCatching { r.lookup(hostname) }.getOrNull()
            if (!res.isNullOrEmpty() && res.none { it.isAnyLocalAddress }) return res.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
        return Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}
