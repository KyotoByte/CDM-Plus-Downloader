package io.github.kyotobyte.cdmplus.vpn

/**
 * IPv4/UDP packet handling for the local DNS-only tunnel. Only packets addressed to the
 * virtual DNS server reach us (the tunnel routes nothing else), so this stays tiny.
 */
object IpPacket {
    class Udp(val packet: ByteArray, val ihl: Int, val srcPort: Int, val dstPort: Int, val payloadOff: Int, val payloadLen: Int)

    /** Returns the UDP view of an IPv4 packet, or null if it isn't IPv4/UDP. */
    fun parseUdp(p: ByteArray, len: Int): Udp? {
        if (len < 28) return null
        if ((p[0].toInt() shr 4) != 4) return null
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((p[9].toInt() and 0xff) != 17) return null
        val total = DnsMessage.u16(p, 2).coerceAtMost(len)
        val udpLen = DnsMessage.u16(p, ihl + 4)
        val payloadLen = minOf(udpLen - 8, total - ihl - 8)
        if (payloadLen <= 0) return null
        return Udp(p, ihl, DnsMessage.u16(p, ihl), DnsMessage.u16(p, ihl + 2), ihl + 8, payloadLen)
    }

    /** Builds the reply packet: addresses and ports swapped, fresh IPv4 header checksum, UDP checksum. */
    fun reply(req: Udp, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        val p = req.packet
        out[0] = 0x45; out[1] = 0
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[4] = 0; out[5] = 0; out[6] = 0x40; out[7] = 0 // DF
        out[8] = 64; out[9] = 17
        System.arraycopy(p, 16, out, 12, 4) // src = old dst
        System.arraycopy(p, 12, out, 16, 4) // dst = old src
        val hc = checksum(out, 0, 20, 0)
        out[10] = (hc shr 8).toByte(); out[11] = hc.toByte()
        out[20] = (req.dstPort shr 8).toByte(); out[21] = req.dstPort.toByte()
        out[22] = (req.srcPort shr 8).toByte(); out[23] = req.srcPort.toByte()
        val ul = 8 + payload.size
        out[24] = (ul shr 8).toByte(); out[25] = ul.toByte()
        System.arraycopy(payload, 0, out, 28, payload.size)
        // UDP checksum over the pseudo header
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += DnsMessage.u16(out, i)
        pseudo += 17 + ul
        var uc = checksum(out, 20, ul, pseudo)
        if (uc == 0) uc = 0xffff
        out[26] = (uc shr 8).toByte(); out[27] = uc.toByte()
        return out
    }

    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        while (i + 1 < off + len) { sum += DnsMessage.u16(b, i); i += 2 }
        if (i < off + len) sum += (b[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    /** Test/helper: wraps a DNS payload into an IPv4/UDP packet. */
    fun build(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val fake = ByteArray(28)
        System.arraycopy(dst, 0, fake, 12, 4); System.arraycopy(src, 0, fake, 16, 4)
        return reply(Udp(fake, 20, dstPort, srcPort, 28, 0), payload)
    }
}
