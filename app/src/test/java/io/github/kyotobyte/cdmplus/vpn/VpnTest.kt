package io.github.kyotobyte.cdmplus.vpn

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

class VpnTest {
    private val out = File(System.getProperty("cdm.vpn.out") ?: "build/vpn-test.txt")
    private fun log(s: String) { println(s); out.appendText(s + "\n") }

    private val app = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns = byteArrayOf(10, 111, 222.toByte(), 2)

    private fun packetFor(name: String, type: Int = DnsMessage.TYPE_A) =
        IpPacket.build(app, dns, 40000, 53, DnsMessage.query(name, type, 0xBEEF))

    @Test fun packetParsingAndBlockedReply() {
        val pkt = packetFor("ads.doubleclick.net")
        // header checksum of a valid packet verifies to 0
        assertEquals(0, IpPacket.checksum(pkt, 0, 20, 0))
        val core = DnsTunnelCore(DnsFilter(Blocklist.of(listOf("doubleclick.net")), emptySet(), emptySet()), { error("must not resolve") })
        val reply = core.handle(pkt, pkt.size)!!
        assertEquals(0, IpPacket.checksum(reply, 0, 20, 0))
        val udp = IpPacket.parseUdp(reply, reply.size)!!
        assertEquals(53, udp.srcPort); assertEquals(40000, udp.dstPort)
        assertArrayEquals(dns, reply.copyOfRange(12, 16)); assertArrayEquals(app, reply.copyOfRange(16, 20))
        val msg = reply.copyOfRange(udp.payloadOff, udp.payloadOff + udp.payloadLen)
        assertEquals(0xBEEF, DnsMessage.u16(msg, 0))
        val ans = DnsMessage.answers(msg)
        assertEquals(1, ans.size); assertArrayEquals(ByteArray(4), ans[0].data)
        // UDP checksum verifies
        var pseudo = 0L; for (i in 12 until 20 step 2) pseudo += DnsMessage.u16(reply, i); pseudo += 17 + udp.payloadLen + 8
        assertEquals(0, IpPacket.checksum(reply, 20, udp.payloadLen + 8, pseudo))
        assertEquals(1, core.stats.blocked.get())
        log("packet: blocked reply for ads.doubleclick.net = 0.0.0.0, IPv4 + UDP checksums valid, id kept")
    }

    @Test fun allUpstreamsResolveOverHttps() {
        for (u in Upstream.entries) {
            val r = DohResolver(u)
            val t0 = System.nanoTime()
            val ans = DnsMessage.answers(r.resolve(DnsMessage.query("example.com", 1, 7)))
            val ms = (System.nanoTime() - t0) / 1_000_000
            val ips = ans.filter { it.type == 1 }.map { InetAddress.getByAddress(it.data).hostAddress }
            assertTrue("${u.title} gave no A record", ips.isNotEmpty())
            log("DoH ${u.title} (${u.url}): example.com -> ${ips.take(2)} in $ms ms")
        }
        // Family resolver filters adult sites (Cloudflare answers 0.0.0.0)
        val fam = DnsMessage.answers(DohResolver(Upstream.CLOUDFLARE_FAMILY).resolve(DnsMessage.query("pornhub.com", 1, 9)))
            .filter { it.type == 1 }.map { InetAddress.getByAddress(it.data).hostAddress }
        val std = DnsMessage.answers(DohResolver(Upstream.CLOUDFLARE).resolve(DnsMessage.query("pornhub.com", 1, 9)))
            .filter { it.type == 1 }.map { InetAddress.getByAddress(it.data).hostAddress }
        log("1.1.1.3 adult filter: pornhub.com -> $fam (1.1.1.1 gives $std)")
        assertTrue(fam.all { it == "0.0.0.0" })
    }

    @Test fun realBlocklistsDownloadAndMatch() {
        val http = OkHttpClient()
        val all = ArrayList<String>()
        for (s in BlockSource.entries) {
            val t0 = System.nanoTime()
            val body = http.newCall(Request.Builder().url(s.url).header("User-Agent", "CDMPlus/1.1").build()).execute().use { it.body!!.string() }
            val d = ListParser.parse(body)
            log("list ${s.title}: ${body.length / 1024} KB -> ${d.size} domains (${(System.nanoTime() - t0) / 1_000_000} ms)")
            assertTrue("${s.title} parsed too few", d.size > 1000)
            all += d
        }
        val t1 = System.nanoTime()
        val bl = Blocklist.of(all)
        log("combined: ${all.size} entries -> ${bl.size} unique, built in ${(System.nanoTime() - t1) / 1_000_000} ms, ~${bl.size * 8 / 1024} KB in memory")
        val f = DnsFilter(bl, DnsFilter.userSet("allowed-example.doubleclick.net"), DnsFilter.userSet("myblock.test.com"))
        assertTrue(f.isBlocked("doubleclick.net")); assertTrue(f.isBlocked("stats.g.doubleclick.net"))
        assertFalse(f.isBlocked("google.com")); assertFalse(f.isBlocked("github.com")); assertFalse(f.isBlocked("speed.cloudflare.com"))
        assertFalse(f.isBlocked("allowed-example.doubleclick.net")); assertTrue(f.isBlocked("a.myblock.test.com"))
        val t2 = System.nanoTime(); repeat(100_000) { f.isBlocked("cdn$it.example-site.co.in") }
        log("lookup speed: 100k names in ${(System.nanoTime() - t2) / 1_000_000} ms; doubleclick blocked, google/github not; allow + deny lists respected")
    }

    @Test fun fullTunnelPathWithRealDoh() {
        val doh = DohResolver(Upstream.CLOUDFLARE)
        val core = DnsTunnelCore(DnsFilter(Blocklist.of(listOf("doubleclick.net")), emptySet(), emptySet()), doh::resolve)
        for (name in listOf("example.com", "github.com", "googleads.g.doubleclick.net")) {
            val pkt = packetFor(name)
            val reply = core.handle(pkt, pkt.size)!!
            val u = IpPacket.parseUdp(reply, reply.size)!!
            val ips = DnsMessage.answers(reply.copyOfRange(u.payloadOff, u.payloadOff + u.payloadLen)).filter { it.type == 1 }.map { InetAddress.getByAddress(it.data).hostAddress }
            log("tunnel: $name -> $ips")
            assertTrue(ips.isNotEmpty())
        }
        assertEquals(3, core.stats.queries.get()); assertEquals(1, core.stats.blocked.get())
    }

    @Test fun downloadsUseEncryptedDns() {
        val doh = DohResolver(Upstream.CLOUDFLARE)
        val ips = DohDns { doh }.lookup("ipv4.download.thinkbroadband.com")
        assertTrue(ips.isNotEmpty() && ips[0] is Inet4Address)
        val client = OkHttpClient.Builder().dns(DohDns { doh }).build()
        val n = client.newCall(Request.Builder().url("http://ipv4.download.thinkbroadband.com/5MB.zip").build()).execute().use { it.body!!.bytes().size }
        assertEquals(5 * 1024 * 1024, n)
        log("downloads with encrypted DNS: resolved thinkbroadband over DoH -> ${ips.first().hostAddress}, fetched 5 MB file OK")
    }
}
