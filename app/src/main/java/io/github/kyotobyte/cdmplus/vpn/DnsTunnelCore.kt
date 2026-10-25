package io.github.kyotobyte.cdmplus.vpn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Live counters shown in the app and the notification. */
class DnsStats {
    val queries = AtomicLong()
    val blocked = AtomicLong()
    val failed = AtomicLong()
    private val recent = ArrayDeque<String>()
    val topBlocked = ConcurrentHashMap<String, Long>()

    fun onBlocked(name: String) {
        blocked.incrementAndGet()
        topBlocked.merge(name, 1L, Long::plus)
        synchronized(recent) { recent.addFirst(name); while (recent.size > 30) recent.removeLast() }
    }
    fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }
}

/**
 * Pure-Kotlin heart of the 1.1.1.1 mode: takes a raw IPv4 packet sent to the virtual DNS
 * server, returns the reply packet (or null to drop it). Blocked names are answered locally;
 * everything else goes out encrypted over DNS-over-HTTPS.
 */
class DnsTunnelCore(
    @Volatile var filter: DnsFilter,
    @Volatile var resolver: (ByteArray) -> ByteArray,
    val stats: DnsStats = DnsStats(),
) {
    fun handle(packet: ByteArray, len: Int): ByteArray? {
        val udp = IpPacket.parseUdp(packet, len) ?: return null
        if (udp.dstPort != 53) return null
        val query = packet.copyOfRange(udp.payloadOff, udp.payloadOff + udp.payloadLen)
        val q = DnsMessage.question(query) ?: return null
        stats.queries.incrementAndGet()
        val answer = if (filter.isBlocked(q.name)) {
            stats.onBlocked(q.name)
            DnsMessage.blockedResponse(query, q)
        } else {
            try { resolver(query) } catch (e: Exception) { stats.failed.incrementAndGet(); DnsMessage.servFail(query, q) }
        }
        return IpPacket.reply(udp, answer)
    }
}
